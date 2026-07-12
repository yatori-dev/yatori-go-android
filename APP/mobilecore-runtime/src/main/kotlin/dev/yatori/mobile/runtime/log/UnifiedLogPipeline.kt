package dev.yatori.mobile.runtime.log

import dev.yatori.mobile.api.dto.LogEntry
import dev.yatori.mobile.api.dto.LogResult
import dev.yatori.mobile.api.dto.eventTimeMicros
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel

/**
 * Process-wide log ingestion actor. Android entries and edge-triggered Go drains share one
 * micro-batch, are stably ordered by event time, then reach disk/live state in one append.
 */
class UnifiedLogPipeline(
    private val scope: CoroutineScope,
    private val store: EncryptedLogStore,
    private val fetchCoreLogs: suspend () -> LogResult,
    private val clearCoreLogs: suspend () -> Unit,
    private val batchWindowMillis: Long = 300L,
    private val fallbackIntervalMillis: Long = 60_000L,
) {
    private sealed interface Event {
        data class Android(val entry: LogEntry) : Event
        data object CoreAvailable : Event
        data object TimedFlush : Event
        data class ForceFlush(val done: CompletableDeferred<Unit>) : Event
        data class Clear(val done: CompletableDeferred<Unit>) : Event
    }

    private val events = Channel<Event>(capacity = Channel.UNLIMITED)
    private val coreSignalPending = AtomicBoolean(false)

    init {
        scope.launch { runActor() }
        scope.launch {
            while (isActive) {
                delay(fallbackIntervalMillis)
                notifyCoreLogsAvailable()
            }
        }
    }

    fun submitAndroid(entry: LogEntry) {
        events.trySend(Event.Android(entry)).getOrThrow()
    }

    /** Coalesces repeated JNI callbacks until the actor has started the corresponding drain. */
    fun notifyCoreLogsAvailable() {
        if (coreSignalPending.compareAndSet(false, true)) {
            events.trySend(Event.CoreAvailable).getOrThrow()
        }
    }

    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        events.send(Event.ForceFlush(done))
        done.await()
    }

    suspend fun clear() {
        val done = CompletableDeferred<Unit>()
        events.send(Event.Clear(done))
        done.await()
    }

    private suspend fun runActor() {
        val pending = ArrayList<LogEntry>()
        var flushJob: Job? = null

        fun scheduleFlush() {
            if (pending.isNotEmpty() && flushJob?.isActive != true) {
                flushJob = scope.launch {
                    delay(batchWindowMillis)
                    events.send(Event.TimedFlush)
                }
            }
        }

        fun flushPending(): Result<Unit> {
            flushJob?.cancel()
            flushJob = null
            if (pending.isEmpty()) return Result.success(Unit)
            return runCatching { store.appendEntries(stableChronologicalOrder(pending)) }
                .onSuccess { pending.clear() }
        }

        for (event in events) {
            when (event) {
                is Event.Android -> {
                    pending += event.entry
                    scheduleFlush()
                }
                Event.CoreAvailable -> {
                    coreSignalPending.set(false)
                    runCatching { fetchCoreLogs() }
                        .getOrNull()
                        ?.logs
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { pending += it }
                    scheduleFlush()
                }
                Event.TimedFlush -> {
                    if (flushPending().isFailure) scheduleFlush()
                }
                is Event.ForceFlush -> {
                    flushPending().fold(
                        onSuccess = { event.done.complete(Unit) },
                        onFailure = { event.done.completeExceptionally(it) },
                    )
                }
                is Event.Clear -> {
                    flushJob?.cancel()
                    flushJob = null
                    coreSignalPending.set(false)
                    runCatching {
                        clearCoreLogs()
                        store.clearCurrentSession()
                    }.fold(
                        onSuccess = {
                            pending.clear()
                            event.done.complete(Unit)
                        },
                        onFailure = {
                            scheduleFlush()
                            event.done.completeExceptionally(it)
                        },
                    )
                }
            }
        }
    }
}

/** Stable event-time order; malformed legacy timestamps remain deterministic at the end. */
fun stableChronologicalOrder(entries: List<LogEntry>): List<LogEntry> =
    entries.withIndex()
        .sortedWith(compareBy<IndexedValue<LogEntry>>(
            { it.value.eventTimeMicros() ?: Long.MAX_VALUE },
            { it.index },
        ))
        .map { it.value }
