package dev.yatori.mobile.runtime.log

import dev.yatori.mobile.api.dto.LogEntry

/**
 * Summarizes repetitive Android-generated operation events so they don't flood the log.
 *
 * Keyed by `operationId + platform + failureType`. Within a [windowMillis] window the first
 * event for a key is emitted immediately; subsequent identical-key events are counted and
 * folded into a single summary entry emitted when the window rolls over.
 *
 * IMPORTANT: this applies ONLY to Android-side repetitive events (e.g. silent OCR retries).
 * Go/mobilecore original log entries are NEVER passed through here — they go straight to the
 * EncryptedLogStore unchanged and unmerged (see brief: Go core logs must not be dropped or merged).
 *
 * Success / user-cancel / substantive errors should bypass summarization (emit immediately).
 */
class LogSummarizer(
    private val windowMillis: Long = 30_000L,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private data class Window(val startMillis: Long, var count: Int, val sample: LogEntry)

    private val windows = LinkedHashMap<String, Window>()
    private var idSeq = -1L // negative ids mark Android-synthesized summary entries

    /**
     * Offer a repetitive Android event. Returns the entries to actually write right now:
     * either the first occurrence (emitted immediately) or, when a window has elapsed, a
     * rolled-up summary for that key. Returns empty when the event is folded into a pending window.
     */
    @Synchronized
    fun offer(operationId: String, platform: String, failureType: String, entry: LogEntry): List<LogEntry> {
        val key = "$operationId|$platform|$failureType"
        val t = now()
        val w = windows[key]
        return when {
            w == null -> {
                windows[key] = Window(t, 0, entry)
                listOf(entry) // first occurrence emitted immediately
            }
            t - w.startMillis >= windowMillis -> {
                val summary = summaryEntry(w, failureType, platform, t)
                windows[key] = Window(t, 0, entry)
                listOf(summary)
            }
            else -> {
                w.count += 1
                emptyList()
            }
        }
    }

    /** Flush all pending windows into summary entries (e.g. when an operation finishes). */
    @Synchronized
    fun flush(): List<LogEntry> {
        val t = now()
        val out = windows.entries.mapNotNull { (key, w) ->
            if (w.count > 0) {
                val (_, platform, failureType) = key.split("|").let { Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
                summaryEntry(w, failureType, platform, t)
            } else null
        }
        windows.clear()
        return out
    }

    private fun summaryEntry(w: Window, failureType: String, platform: String, t: Long): LogEntry =
        w.sample.copy(
            id = idSeq--,
            message = "${w.sample.message} (×${w.count + 1} in ${(t - w.startMillis) / 1000}s, summarized)",
        )
}
