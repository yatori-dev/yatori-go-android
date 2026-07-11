package dev.yatori.mobile.runtime.operation

import com.google.gson.JsonObject
import dev.yatori.mobile.api.dto.RunTaskResult
import dev.yatori.mobile.api.dto.SessionData
import dev.yatori.mobile.api.dto.TaskItem
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class PlatformTaskRunOptions(
    val dryRun: Boolean = false,
    val maxTicksPerTask: Int = 240,
    /** 1 = normal (30 s incremental, sequential), 2 = fast (submit 100 % once, matches console fastModeAction). */
    val videoModel: Int = 1,
)

interface PlatformTaskRunner {
    fun supports(session: SessionData, task: TaskItem): Boolean

    suspend fun runTask(
        session: SessionData,
        task: TaskItem,
        options: PlatformTaskRunOptions = PlatformTaskRunOptions(),
        shouldCancel: () -> Boolean = { false },
        onEvent: (SyncEvent) -> Unit = {},
    ): RunTaskResult
}

/**
 * Composes platform action primitives into one host-driven task run.
 *
 * The primitive layer persists raw action state. This layer owns cadence, cancellation checks,
 * and platform-specific progress math, while callers own the foreground lifetime.
 */
class PlatformTaskScheduler(
    private val actions: PlatformActionScheduler,
    private val sleepMillis: suspend (Long) -> Unit = { delay(it) },
) : PlatformTaskRunner {

    override fun supports(session: SessionData, task: TaskItem): Boolean = when (session.platform) {
        "haiqikeji", "welearn", "enaea", "cqie", "qingshuxuetang", "ttcdw" -> task.isContentTask()
        "xuexitong" -> task.isXuexitongAutoTask()
        "yinghua" -> task.id == "keepAlive" || task.type.equals("keepAlive", true) || task.type.equals("video", true)
        else -> false
    }

    override suspend fun runTask(
        session: SessionData,
        task: TaskItem,
        options: PlatformTaskRunOptions,
        shouldCancel: () -> Boolean,
        onEvent: (SyncEvent) -> Unit,
    ): RunTaskResult {
        if (options.dryRun) {
            return RunTaskResult(
                session.platform,
                task.id,
                "dry_run",
                "未提交",
            )
        }
        return when (session.platform) {
            "haiqikeji" -> runHaiqikeji(session, task, options, shouldCancel, onEvent)
            "welearn" -> runWelearn(session, task, options, shouldCancel, onEvent)
            "enaea" -> runEnaea(session, task, options, shouldCancel, onEvent)
            "cqie" -> runCqie(session, task, options, shouldCancel, onEvent)
            "qingshuxuetang" -> runQingshuxuetang(session, task, options, shouldCancel, onEvent)
            "ttcdw" -> runTtcdw(session, task, options, shouldCancel, onEvent)
            "xuexitong" -> runXuexitong(session, task, options, shouldCancel, onEvent)
            "yinghua" -> runYinghua(session, task, options, shouldCancel, onEvent)
            else -> error("unsupported platform scheduler: ${session.platform}")
        }
    }

    private suspend fun runHaiqikeji(
        session: SessionData,
        task: TaskItem,
        options: PlatformTaskRunOptions,
        shouldCancel: () -> Boolean,
        onEvent: (SyncEvent) -> Unit,
    ): RunTaskResult {
        var verified = actions.getHaiqikejiProgress(session, task)
        var verifiedProgress = jsonDouble(verified.raw, "progress")
        if (options.videoModel != 2 && verifiedProgress >= 100.0) return verified

        val maxStudyCycles = max(1, options.maxTicksPerTask)
        for (cycle in 1..maxStudyCycles) {
            val started = actions.startHaiqikejiProgress(session, task, verifiedProgress)
            var sessionOpen = true
            suspend fun finishSession(): RunTaskResult {
                sessionOpen = false
                return actions.finishHaiqikejiProgress(session, task.id)
            }

            try {
                val videoDuration = durationSeconds(started.task.raw, "videoDuration", "duration", "timeLength", "videoLength")
                    .takeIf { it > 0 } ?: 30

                if (options.videoModel == 2) {
                    // Console fastModeAction waits 30 s, submits 100 %, ends, then verifies.
                    sleepMillis(30_000L)
                    if (shouldCancel()) return finishSession()
                    actions.tickHaiqikejiProgress(session, task.id, PercentTickInput(100, finish = false))
                    onEvent(tickEvent(session.platform, task, 1, 1, 100))
                } else {
                    // Console normalModeAction resumes from server progress and waits before every 30 s tick.
                    var nowTime = ((verifiedProgress / 100.0) * videoDuration).toInt().coerceAtLeast(0)
                    val ticks = max(1, ceil((videoDuration - nowTime).toDouble() / 30.0).toInt())
                    for (i in 1..ticks) {
                        if (shouldCancel()) return finishSession()
                        sleepMillis(30_000L)
                        if (shouldCancel()) return finishSession()
                        nowTime = min(videoDuration, nowTime + 30)
                        val submitProgress = min(100, (nowTime.toDouble() / videoDuration * 100).toInt())
                        actions.tickHaiqikejiProgress(
                            session,
                            task.id,
                            PercentTickInput(submitProgress, finish = false),
                        )
                        onEvent(tickEvent(session.platform, task, i, ticks, submitProgress))
                        if (nowTime >= videoDuration) break
                    }
                }

                finishSession()
            } catch (cause: Throwable) {
                if (sessionOpen) {
                    val cleanupFailure = withContext(NonCancellable) {
                        runCatching { finishSession() }.exceptionOrNull()
                    }
                    cleanupFailure?.let(cause::addSuppressed)
                }
                throw cause
            }

            verified = actions.getHaiqikejiProgress(session, task.id)
            verifiedProgress = jsonDouble(verified.raw, "progress")
            if (verifiedProgress >= 100.0) return verified

            onEvent(SyncEvent("warn", "${task.name.ifBlank { task.id }} 服务端进度=$verifiedProgress%，重新学习", session.platform))
            if (options.videoModel == 2 && cycle < maxStudyCycles) {
                // fastModeAction also waits after a failed verification before opening the next session.
                sleepMillis(30_000L)
            }
        }

        error(
            "haiqikeji: progress remained below 100 after $maxStudyCycles study cycles " +
                "(last=$verifiedProgress)",
        )
    }

    private suspend fun runWelearn(
        session: SessionData,
        task: TaskItem,
        options: PlatformTaskRunOptions,
        shouldCancel: () -> Boolean,
        onEvent: (SyncEvent) -> Unit,
    ): RunTaskResult {
        // Mode 2: one-shot complete (mirrors console nodeCompleteAction / WeLearnCompletePointAction).
        if (options.videoModel == 2) {
            val result = actions.completeWelearnPoint(session, task)
            onEvent(tickEvent(session.platform, task, 1, 1, 100))
            return result
        }
        // Mode 1: SCORM time-accumulation loop, 60 s ticks (mirrors console nodeSubmitTimeAction).
        actions.startWelearnProgress(session, task)
        val totalTime = durationSeconds(task.raw, "totalTime", "duration", "timeLength", "videoLength")
            .takeIf { it > 0 } ?: 60
        val ticks = min(options.maxTicksPerTask, max(1, ceil(totalTime / 60.0).toInt()))
        var last = RunTaskResult(session.platform, task.id, "started")
        for (i in 1..ticks) {
            if (shouldCancel()) return last
            sleepBeforeTick(i, 60)
            val sessionTime = min(i * 60, totalTime)
            last = actions.tickWelearnProgress(
                session,
                task.id,
                WelearnTickInput(sessionTime = sessionTime, totalTime = totalTime, finish = false),
            )
            onEvent(tickEvent(session.platform, task, i, ticks, percent(i, ticks)))
        }
        if (!shouldCancel()) {
            last = actions.tickWelearnProgress(
                session,
                task.id,
                WelearnTickInput(sessionTime = totalTime, totalTime = totalTime, finish = true),
            )
        }
        return last
    }

    private suspend fun runEnaea(
        session: SessionData,
        task: TaskItem,
        options: PlatformTaskRunOptions,
        shouldCancel: () -> Boolean,
        onEvent: (SyncEvent) -> Unit,
    ): RunTaskResult {
        if (options.videoModel == 0) {
            return RunTaskResult(session.platform, task.id, "skipped", "video learning disabled")
        }
        val input = if (options.videoModel == 2) {
            EnaeaTickInput(studyTime = 60, fast = true)
        } else {
            EnaeaTickInput()
        }
        var last = RunTaskResult(session.platform, task.id, "started")
        for (i in 1..options.maxTicksPerTask) {
            if (shouldCancel()) return last
            sleepBeforeTick(i, 25)
            last = actions.tickEnaeaProgress(session, task, input)
            val progress = jsonDouble(last.raw, "progress")
            onEvent(tickEvent(session.platform, task, i, options.maxTicksPerTask, progress.toInt()))
            if (progress >= 100.0) break
        }
        val finalProgress = jsonDouble(last.raw, "progress")
        return if (finalProgress >= 100.0) {
            last
        } else {
            last.copy(status = "incomplete", message = "progress below 100 after tick cap")
        }
    }

    private suspend fun runCqie(
        session: SessionData,
        task: TaskItem,
        options: PlatformTaskRunOptions,
        shouldCancel: () -> Boolean,
        onEvent: (SyncEvent) -> Unit,
    ): RunTaskResult {
        if (options.videoModel == 0) {
            return RunTaskResult(session.platform, task.id, "skipped", "video learning disabled")
        }
        val started = actions.startCqieProgress(session, task)
        val start = max(0, started.progress.toInt())
        suspend fun finishAndVerify(position: Int): RunTaskResult {
            actions.finishCqieProgress(session, task.id, position)
            val verified = actions.getCqieProgress(session, task.id)
            val progress = jsonDouble(verified.raw, "progress")
            if (!verified.status.equals("done", ignoreCase = true) || progress < 100.0) {
                error("cqie: server progress remained below 100 after final save (status=${verified.status}, progress=$progress)")
            }
            return verified
        }

        // Mode 2 (秒刷): Console performs Save(current) -> Submit(current) -> Save(current).
        if (options.videoModel == 2) {
            actions.tickCqieProgress(session, task.id, CqieTickInput(startPos = start, stopPos = start, maxPos = start))
            onEvent(tickEvent(session.platform, task, 1, 1, 100))
            return finishAndVerify(start)
        }

        // Mode 1: Console submits [current,current,current+3], waits three seconds, then saves at duration.
        val total = durationSeconds(started.task.raw, "timeLength", "duration", "videoLength").takeIf { it > 0 } ?: 3
        val ticks = ceil((total - start).coerceAtLeast(0) / 3.0).toInt()
        var last = RunTaskResult(session.platform, task.id, "started")
        var pos = start
        for (i in 1..ticks) {
            if (shouldCancel()) return last
            val next = min(total, pos + 3)
            last = actions.tickCqieProgress(
                session,
                task.id,
                CqieTickInput(startPos = pos, stopPos = pos, maxPos = next),
            )
            pos = next
            onEvent(tickEvent(session.platform, task, i, ticks, percent(i, ticks)))
            sleepMillis(3_000L)
        }
        if (shouldCancel()) return last
        return finishAndVerify(total)
    }

    private suspend fun runQingshuxuetang(
        session: SessionData,
        task: TaskItem,
        options: PlatformTaskRunOptions,
        shouldCancel: () -> Boolean,
        onEvent: (SyncEvent) -> Unit,
    ): RunTaskResult {
        actions.startQingshuxuetangProgress(session, task)
        val total = durationSeconds(task.raw, "totalTime", "duration", "timeLength", "videoLength").takeIf { it > 0 } ?: 60
        val ticks = min(options.maxTicksPerTask, max(1, ceil(total / 60.0).toInt()))
        var last = RunTaskResult(session.platform, task.id, "started")
        for (i in 1..ticks) {
            if (shouldCancel()) return last
            // Console waits 60 s before every submission, including the first one.
            sleepMillis(60_000L)
            if (shouldCancel()) return last
            val position = min(total, i * 60)
            last = actions.tickQingshuxuetangProgress(
                session,
                task.id,
                QingshuxuetangTickInput(position = position, finish = position >= total),
            )
            onEvent(tickEvent(session.platform, task, i, ticks, percent(i, ticks)))
        }
        if (shouldCancel()) return last
        return actions.refreshQingshuxuetangScore(session, task)
    }

    private suspend fun runTtcdw(
        session: SessionData,
        task: TaskItem,
        options: PlatformTaskRunOptions,
        shouldCancel: () -> Boolean,
        onEvent: (SyncEvent) -> Unit,
    ): RunTaskResult {
        val total = durationSeconds(task.raw, "duration", "timeLength", "videoLength").takeIf { it > 0 } ?: 30
        val useDes = jsonString(task.raw, "submitMode") == "des" || jsonString(task.raw, "tickerData").isNotBlank()
        val ticks = min(options.maxTicksPerTask, max(1, ceil(total / 30.0).toInt()))
        var last = RunTaskResult(session.platform, task.id, "prepared")
        if (useDes) {
            actions.prepareTtcdwTicker(session, task, TtcdwTickerInput(playedEnd = min(30, total).toDouble()))
            var start = 0.0
            for (i in 1..ticks) {
                if (shouldCancel()) return last
                sleepBeforeTick(i, 30)
                val end = min(total, i * 30).toDouble()
                last = actions.tickTtcdwTicker(
                    session,
                    task.id,
                    TtcdwTickerInput(playedStart = start, playedEnd = end, realSubmit = true),
                )
                start = end
                onEvent(tickEvent(session.platform, task, i, ticks, percent(i, ticks)))
            }
            return last
        }

        actions.prepareTtcdwProgress(session, task)
        for (i in 1..ticks) {
            if (shouldCancel()) return last
            sleepBeforeTick(i, 30)
            val progress = min(total, i * 30).toDouble()
            last = actions.tickTtcdwProgress(
                session,
                task.id,
                TtcdwTickInput(progress = progress, realSubmit = true, finish = progress >= total),
            )
            onEvent(tickEvent(session.platform, task, i, ticks, percent(i, ticks)))
        }
        return last
    }

    private suspend fun runXuexitong(
        session: SessionData,
        task: TaskItem,
        options: PlatformTaskRunOptions,
        shouldCancel: () -> Boolean,
        onEvent: (SyncEvent) -> Unit,
    ): RunTaskResult = when {
        task.type.equals("video", true) -> runXuexitongVideo(session, task, options, shouldCancel, onEvent)
        task.type.equals("audio", true) -> runXuexitongAudio(session, task, options, shouldCancel, onEvent)
        task.type.equals("live", true) -> runXuexitongLive(session, task, options, shouldCancel, onEvent)
        task.type.equals("document", true) -> actions.runXuexitongDocument(session, task)
        task.type.equals("read", true) -> actions.runXuexitongDocument(session, task)
        task.type.equals("hyperlink", true) -> actions.runXuexitongHyperlink(session, task)
        task.type.equals("monitor", true) || task.type.equals("keepAlive", true) -> actions.tickXuexitongMonitor(session)
        else -> error("unsupported xuexitong task type: ${task.type}")
    }

    private suspend fun runYinghua(
        session: SessionData,
        task: TaskItem,
        options: PlatformTaskRunOptions,
        shouldCancel: () -> Boolean,
        onEvent: (SyncEvent) -> Unit,
    ): RunTaskResult {
        if (task.id == "keepAlive" || task.type.equals("keepAlive", true)) {
            // Yinghua re-login requires captcha OCR, which the runtime module cannot complete.
            // Surface expiry to CourseSyncManager/WorkManager instead of creating a dangling challenge.
            return actions.tickYinghuaKeepAlive(session, reloginOnExpired = false).result
        }

        // 去红模式: only process nodes flagged with parallel-play warning; submit the original
        // viewedDuration once with studyId=0, then wait 8 s before the next node/refetch. This is
        // the exact request order used by console videoBadRedAction.
        if (options.videoModel == 3) {
            val errorMsg = runCatching { task.raw.get("errorMessage")?.asString ?: "" }.getOrDefault("")
            if (errorMsg != "检测到可能使用并行播放刷课") {
                return RunTaskResult(session.platform, task.id, "skipped", "no red flag")
            }
            if (shouldCancel()) return RunTaskResult(session.platform, task.id, "skipped", "cancelled before red submit")
            val viewedDuration = durationSeconds(task.raw, "viewedDuration").coerceAtLeast(0)
            val result = actions.submitYinghuaRedProgress(session, task, YinghuaTickInput(studyTime = viewedDuration))
            onEvent(tickEvent(session.platform, task, 1, 1, viewedDuration))
            if (!shouldCancel()) sleepMillis(8_000L)
            return result
        }

        // Mode 1 (normal) and 2 (violence): same per-node sequential 5 s ticks.
        // Concurrency for mode 2 is handled at CourseSyncManager level.
        val started = actions.startYinghuaProgress(session, task)
        val total = durationSeconds(started.task.raw, "videoDuration", "duration", "timeLength", "videoLength").takeIf { it > 0 } ?: 5
        var position = durationSeconds(started.task.raw, "viewedDuration").coerceAtLeast(0)
        if (position >= total) return RunTaskResult(session.platform, task.id, "done", raw = started.raw)
        // The console runs until the full video duration is reached. Capping this loop at the
        // generic maxTicksPerTask (240 by default) silently stopped Yinghua videos after 20 minutes
        // and still returned "submitted", so the batch layer persisted them as completed.
        val ticks = max(1, ceil((total - position).coerceAtLeast(0) / 5.0).toInt())
        var last = RunTaskResult(session.platform, task.id, "started", raw = started.raw)
        for (i in 1..ticks) {
            if (shouldCancel()) return last
            sleepBeforeTick(i, 5)
            position = min(total, position + 5)
            last = actions.tickYinghuaProgress(session, task.id, YinghuaTickInput(studyTime = position))
            onEvent(tickEvent(session.platform, task, i, ticks, ((position.toDouble() / max(1, total)) * 100.0).toInt().coerceIn(1, 100)))
            if (position >= total) break
        }
        return last
    }

    private suspend fun runXuexitongAudio(
        session: SessionData,
        task: TaskItem,
        options: PlatformTaskRunOptions,
        shouldCancel: () -> Boolean,
        onEvent: (SyncEvent) -> Unit,
    ): RunTaskResult {
        val prepared = actions.prepareXuexitongAudio(session, task)
        if (prepared.status == "done" || prepared.status == "skipped" || jsonBool(prepared.raw, "isPassed")) {
            return RunTaskResult(session.platform, task.id, prepared.status, raw = prepared.raw)
        }
        val total = durationSeconds(prepared.task.raw, "duration", "timeLength", "videoLength").takeIf { it > 0 } ?: 58
        val rawPlayingTime = durationSeconds(prepared.task.raw, "playingTime").coerceAtLeast(0)
        var position = if (rawPlayingTime >= total) 0 else rawPlayingTime
        if (shouldCancel()) return RunTaskResult(session.platform, task.id, "prepared", raw = prepared.raw)

        // Match console: submit the current position once as a start event (isdrag=3),
        // then wait the real elapsed time before advancing the reported position.
        var last = actions.tickXuexitongAudio(
            session,
            task.id,
            XuexitongMediaTickInput(progress = position, realSubmit = true, isDrag = 3),
        )
        val expectedTicks = max(1, ceil((total - position).coerceAtLeast(0) / 58.0).toInt())
        var tickIndex = 0
        while (position < total && tickIndex < options.maxTicksPerTask && !jsonBool(last.raw, "isPassed")) {
            val delta = min(58, total - position)
            sleepMillis(delta * 1000L)
            if (shouldCancel()) return last
            position += delta
            tickIndex++
            last = actions.tickXuexitongAudio(
                session,
                task.id,
                XuexitongMediaTickInput(progress = position, realSubmit = true, isDrag = 0),
            )
            onEvent(tickEvent(session.platform, task, tickIndex, expectedTicks, ((position.toDouble() / max(1, total)) * 100.0).toInt().coerceIn(1, 100)))
        }
        if (position < total && !jsonBool(last.raw, "isPassed")) {
            error("xuexitong audio exceeded maxTicksPerTask before reaching duration: $position/$total")
        }

        val limitTime = max(500, total / 2)
        var overTime = 0
        val extendSec = 5
        while (!shouldCancel() && overTime < limitTime && !jsonBool(last.raw, "isPassed")) {
            sleepMillis(extendSec * 1000L)
            overTime += extendSec
            last = actions.tickXuexitongAudio(
                session,
                task.id,
                XuexitongMediaTickInput(progress = total, realSubmit = true, isDrag = 0),
            )
            onEvent(tickEvent(session.platform, task, expectedTicks + (overTime / extendSec), expectedTicks, 100))
        }
        if (shouldCancel()) return last
        if (!jsonBool(last.raw, "isPassed")) {
            error("xuexitong audio server did not confirm isPassed after ${limitTime}s overtime")
        }
        sleepMillis((Random.nextInt(51) + 10) * 1000L)
        return last
    }

    private suspend fun runXuexitongVideo(
        session: SessionData,
        task: TaskItem,
        options: PlatformTaskRunOptions,
        shouldCancel: () -> Boolean,
        onEvent: (SyncEvent) -> Unit,
    ): RunTaskResult {
        val prepared = actions.prepareXuexitongVideo(session, task)
        if (prepared.status == "done" || prepared.status == "skipped" || jsonBool(prepared.raw, "isPassed")) {
            return RunTaskResult(session.platform, task.id, prepared.status, raw = prepared.raw)
        }
        val total = durationSeconds(prepared.task.raw, "duration", "timeLength", "videoLength").takeIf { it > 0 } ?: 58
        val rawPlayingTime = durationSeconds(prepared.task.raw, "playingTime").coerceAtLeast(0)
        var position = if (rawPlayingTime >= total) 0 else rawPlayingTime
        if (shouldCancel()) return RunTaskResult(session.platform, task.id, "prepared", raw = prepared.raw)

        // The first heartbeat reports the current position as a start event. Progress is only
        // advanced after the corresponding real elapsed time, including short (<58 s) videos.
        var last = actions.tickXuexitongVideo(
            session,
            task.id,
            XuexitongMediaTickInput(progress = position, realSubmit = true, isDrag = 3),
        )
        val expectedTicks = max(1, ceil((total - position).coerceAtLeast(0) / 58.0).toInt())
        var tickIndex = 0
        while (position < total && tickIndex < options.maxTicksPerTask && !jsonBool(last.raw, "isPassed")) {
            val delta = min(58, total - position)
            sleepMillis(delta * 1000L)
            if (shouldCancel()) return last
            position += delta
            tickIndex++
            last = actions.tickXuexitongVideo(
                session,
                task.id,
                XuexitongMediaTickInput(progress = position, realSubmit = true, isDrag = 0),
            )
            onEvent(tickEvent(session.platform, task, tickIndex, expectedTicks, ((position.toDouble() / max(1, total)) * 100.0).toInt().coerceIn(1, 100)))
        }
        if (position < total && !jsonBool(last.raw, "isPassed")) {
            error("xuexitong video exceeded maxTicksPerTask before reaching duration: $position/$total")
        }

        val limitTime = max(500, total / 2)
        var overTime = 0
        val extendSec = 5
        while (!shouldCancel() && overTime < limitTime && !jsonBool(last.raw, "isPassed")) {
            sleepMillis(extendSec * 1000L)
            overTime += extendSec
            last = actions.tickXuexitongVideo(
                session,
                task.id,
                XuexitongMediaTickInput(progress = total, realSubmit = true, isDrag = 0),
            )
            onEvent(tickEvent(session.platform, task, expectedTicks + (overTime / extendSec), expectedTicks, 100))
        }
        if (shouldCancel()) return last
        if (!jsonBool(last.raw, "isPassed")) {
            error("xuexitong video server did not confirm isPassed after ${limitTime}s overtime")
        }
        sleepMillis((Random.nextInt(51) + 10) * 1000L)
        return last
    }

    private suspend fun runXuexitongLive(
        session: SessionData,
        task: TaskItem,
        options: PlatformTaskRunOptions,
        shouldCancel: () -> Boolean,
        onEvent: (SyncEvent) -> Unit,
    ): RunTaskResult {
        val prepared = actions.prepareXuexitongLive(session, task)
        if (prepared.status == "done" || jsonDouble(prepared.raw, "percent") >= 90.0) {
            return RunTaskResult(session.platform, task.id, prepared.status, raw = prepared.raw)
        }
        var last = RunTaskResult(session.platform, task.id, "prepared", raw = prepared.raw)
        for (i in 1..options.maxTicksPerTask) {
            if (shouldCancel()) return last
            sleepBeforeTick(i, 30)
            last = actions.tickXuexitongLive(session, task.id, realSubmit = true)
            val progress = jsonDouble(last.raw, "percent")
            onEvent(tickEvent(session.platform, task, i, options.maxTicksPerTask, progress.toInt()))
            if (last.status == "done" || progress >= 90.0) break
        }
        return last
    }

    private suspend fun sleepBeforeTick(index: Int, intervalSeconds: Int) {
        if (index > 1) sleepMillis(intervalSeconds * 1000L)
    }

    private fun tickEvent(platform: String, task: TaskItem, index: Int, total: Int, progress: Int): SyncEvent =
        SyncEvent("info", "${task.name.ifBlank { task.id }} 提交学时 $index/$total 进度=$progress%", platform)

    private fun TaskItem.isContentTask(): Boolean =
        type.isBlank() ||
            type.equals("video", true) ||
            type.equals("audio", true) ||
            type.equals("course", true) ||
            type.equals("resource", true) ||
            type.equals("document", true)

    private fun TaskItem.isXuexitongAutoTask(): Boolean =
        type.equals("video", true) ||
        type.equals("audio", true) ||
            type.equals("live", true) ||
            type.equals("document", true) ||
            type.equals("read", true) ||
            type.equals("hyperlink", true) ||
            type.equals("monitor", true) ||
            type.equals("keepAlive", true)

    private fun ticksFor(raw: JsonObject, intervalSeconds: Int, maxTicks: Int): Int {
        val duration = durationSeconds(raw, "duration", "timeLength", "videoLength", "totalTime")
        if (duration <= 0) return 1
        return min(maxTicks, max(1, ceil(duration.toDouble() / intervalSeconds.toDouble()).toInt()))
    }

    private fun percent(index: Int, total: Int): Int =
        ((index.toDouble() / max(1, total).toDouble()) * 100.0).toInt().coerceIn(1, 100)

    private fun durationSeconds(raw: JsonObject, vararg keys: String): Int {
        for (key in keys) {
            val value = raw.get(key) ?: continue
            val parsed = runCatching {
                when {
                    value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> value.asInt
                    value.isJsonPrimitive -> value.asString.toDoubleOrNull()?.toInt() ?: 0
                    else -> 0
                }
            }.getOrDefault(0)
            if (parsed > 0) return parsed
        }
        return 0
    }

    private fun jsonDouble(raw: JsonObject, key: String): Double =
        runCatching { raw.get(key)?.asDouble ?: 0.0 }.getOrDefault(0.0)

    private fun jsonBool(raw: JsonObject, key: String): Boolean =
        runCatching {
            val value = raw.get(key) ?: return@runCatching false
            when {
                value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asBoolean
                value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> value.asInt != 0
                value.isJsonPrimitive -> value.asString.equals("true", ignoreCase = true) || value.asString == "1"
                else -> false
            }
        }.getOrDefault(false)

    private fun jsonString(raw: JsonObject, key: String): String =
        runCatching { raw.get(key)?.asString ?: "" }.getOrDefault("")
}
