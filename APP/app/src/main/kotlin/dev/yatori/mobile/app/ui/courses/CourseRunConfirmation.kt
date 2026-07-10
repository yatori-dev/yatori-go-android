package dev.yatori.mobile.app.ui.courses

import com.google.gson.JsonObject
import dev.yatori.mobile.api.dto.MobileConfig
import dev.yatori.mobile.app.platform.platformCourseConfig

internal data class CourseRunConfirmation(
    val title: String,
    val message: String,
    val confirmLabel: String,
)

internal fun buildCourseRunConfirmation(
    platform: String,
    account: String,
    config: MobileConfig?,
    dryRun: Boolean,
): CourseRunConfirmation {
    val user = config?.users?.firstOrNull {
        it.str("account") == account && it.str("accountType").equals(platform, ignoreCase = true)
    }
    val cc = user.obj("coursesCustom")
    val matrix = platformCourseConfig(platform)

    val videoModel = cc.int("videoModel", 1)
    val answerModeValue = cc.int("autoExam")
    val videoMode = matrix.videoModes.firstOrNull { it.value == videoModel }?.label ?: "未配置"
    val answerMode = matrix.autoExamModes.firstOrNull { it.value == cc.int("autoExam") }?.label ?: "未开启"
    val submitMode = matrix.submitModes.firstOrNull { it.value == cc.int("examAutoSubmit") }?.label ?: "只保存"
    val includes = cc.arrayText("includeCourses").ifBlank { "全部课程" }
    val excludes = cc.arrayText("excludeCourses").ifBlank { "无" }
    val highRiskSubmit = !dryRun && matrix.submitModes.isNotEmpty() && answerModeValue != 0 && cc.int("examAutoSubmit") != 0

    val message = buildString {
        append(if (dryRun) "本次运行不会自动提交答案和进度。\n\n" else "本次运行会自动提交答案和进度。\n\n")
        append("课程范围：").append(includes).append('\n')
        append("排除课程：").append(excludes).append('\n')
        append("视频模式：").append(videoMode).append('\n')
        if (matrix.autoExamModes.isNotEmpty()) append("答题模式：").append(answerMode).append('\n')
        if (matrix.submitModes.isNotEmpty() && (matrix.autoExamModes.isEmpty() || answerModeValue != 0)) append("答题提交：").append(submitMode).append('\n')
        if (platform == "xuexitong" && videoModel == 3) {
            append("任务点数量：").append(cc.int("cxNode", 3).coerceAtLeast(1)).append('\n')
        }
        if (platform == "xuexitong" && answerModeValue != 0) {
            append("学习通：章测")
                .append(if (cc.int("cxChapterTestSw", 1) == 1) "开" else "关")
                .append("，作业")
                .append(if (cc.int("cxWorkSw", 1) == 1) "开" else "关")
                .append("，考试")
                .append(if (cc.int("cxExamSw", 1) == 1) "开" else "关")
                .append('\n')
        }
        append('\n')
        append(
            when {
                dryRun -> "可以先看流程和日志，任务不会提交。"
                highRiskSubmit -> "章测/考试会交卷，请确认配置没问题。"
                else -> "章测/考试不会交卷，答案会自动保存。"
            },
        )
    }

    return CourseRunConfirmation(
        title = if (dryRun) "确认试跑" else "确认开始运行",
        message = message,
        confirmLabel = if (dryRun) "开始试跑" else "开始运行",
    )
}

private fun JsonObject?.obj(key: String): JsonObject =
    this?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

private fun JsonObject?.str(key: String): String =
    runCatching { this?.get(key)?.asString.orEmpty() }.getOrDefault("")

private fun JsonObject?.int(key: String, default: Int = 0): Int =
    runCatching { this?.get(key)?.asInt ?: default }.getOrDefault(default)

private fun JsonObject?.arrayText(key: String): String =
    this?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { runCatching { it.asString }.getOrNull() }
        ?.joinToString(", ")
        .orEmpty()
