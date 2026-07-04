package dev.yatori.mobile.runtime.log

import dev.yatori.mobile.api.dto.LogEntry
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogSummarizerTest {

    private fun entry(msg: String) =
        LogEntry(id = 1, time = "t", level = "warn", source = "ocr", platform = "yinghua", message = msg)

    @Test
    fun `first occurrence emitted immediately`() {
        val s = LogSummarizer(windowMillis = 30_000L, now = { 0L })
        val out = s.offer("op1", "yinghua", "ocr_invalid", entry("retry"))
        assertEquals(1, out.size)
        assertEquals("retry", out[0].message)
    }

    @Test
    fun `repeats within window are folded, summary on window roll`() {
        var t = 0L
        val s = LogSummarizer(windowMillis = 30_000L, now = { t })
        assertEquals(1, s.offer("op1", "yinghua", "ocr_invalid", entry("retry")).size) // first → emit
        t = 5_000L
        assertTrue(s.offer("op1", "yinghua", "ocr_invalid", entry("retry")).isEmpty())  // folded
        t = 10_000L
        assertTrue(s.offer("op1", "yinghua", "ocr_invalid", entry("retry")).isEmpty())  // folded
        t = 31_000L
        val summary = s.offer("op1", "yinghua", "ocr_invalid", entry("retry"))           // window rolled → summary
        assertEquals(1, summary.size)
        assertTrue(summary[0].message.contains("summarized"))
        assertTrue(summary[0].id < 0, "summary entries use synthetic negative ids")
    }

    @Test
    fun `distinct keys do not merge`() {
        val s = LogSummarizer(windowMillis = 30_000L, now = { 0L })
        assertEquals(1, s.offer("op1", "yinghua", "ocr_invalid", entry("a")).size)
        assertEquals(1, s.offer("op2", "yinghua", "ocr_invalid", entry("b")).size)
        assertEquals(1, s.offer("op1", "cqie", "ocr_invalid", entry("c")).size)
    }

    @Test
    fun `flush emits pending summaries`() {
        var t = 0L
        val s = LogSummarizer(windowMillis = 30_000L, now = { t })
        s.offer("op1", "yinghua", "ocr_invalid", entry("retry"))
        t = 1_000L
        s.offer("op1", "yinghua", "ocr_invalid", entry("retry")) // folded (count=1)
        val flushed = s.flush()
        assertEquals(1, flushed.size)
        assertTrue(flushed[0].message.contains("summarized"))
    }
}
