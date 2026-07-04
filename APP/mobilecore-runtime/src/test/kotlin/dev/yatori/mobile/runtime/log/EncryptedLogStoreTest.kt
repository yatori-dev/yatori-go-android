package dev.yatori.mobile.runtime.log

import dev.yatori.mobile.api.dto.LogEntry
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class EncryptedLogStoreTest {

    private lateinit var dir: File
    private lateinit var defaultTimeZone: TimeZone
    private val crypto = AesGcmLogCrypto(ByteArray(32) { it.toByte() })

    private fun store(startMillis: Long): EncryptedLogStore =
        EncryptedLogStore(dir, crypto, sessionStartMillis = startMillis)

    private fun entry(id: Long, level: String = "info", msg: String = "m$id") =
        LogEntry(id = id, time = "2026-06-24T00:00:0${id}Z", level = level, source = "mobilecore", platform = "yinghua", message = msg)

    @Before
    fun setUp() {
        defaultTimeZone = TimeZone.getDefault()
        dir = Files.createTempDirectory("logstore-test").toFile()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(defaultTimeZone)
        dir.deleteRecursively()
    }

    @Test
    fun `append then read round-trips entries in order`() {
        val s = store(1_000L)
        s.appendEntries(listOf(entry(1), entry(2)))
        s.appendEntries(listOf(entry(3)))
        val read = s.readCurrentSession()
        assertEquals(listOf(1L, 2L, 3L), read.map { it.id })
        assertEquals("m2", read[1].message)
    }

    @Test
    fun `empty session creates no file`() {
        store(1_000L).appendEntries(emptyList())
        assertEquals(0, dir.listFiles { f -> f.name.endsWith(EncryptedLogStore.EXT) }?.size ?: 0)
    }

    @Test
    fun `file is created lazily on first append`() {
        val s = store(1_000L)
        assertTrue(s.readCurrentSession().isEmpty())
        assertEquals(0, dir.listFiles()?.size ?: 0)
        s.appendEntries(listOf(entry(1)))
        assertEquals(1, dir.listFiles { f -> f.name.endsWith(EncryptedLogStore.EXT) }?.size)
    }

    @Test
    fun `on-disk bytes are not plaintext`() {
        val s = store(1_000L)
        s.appendEntries(listOf(entry(1, msg = "SECRET_TOKEN_VALUE")))
        val raw = dir.listFiles { f -> f.name.endsWith(EncryptedLogStore.EXT) }!!.single().readText(Charsets.ISO_8859_1)
        assertFalse(raw.contains("SECRET_TOKEN_VALUE"), "ciphertext must not contain plaintext message")
    }

    @Test
    fun `clearCurrentSession removes the file`() {
        val s = store(1_000L)
        s.appendEntries(listOf(entry(1)))
        s.clearCurrentSession()
        assertTrue(s.readCurrentSession().isEmpty())
    }

    @Test
    fun `listHistory and readHistory across sessions`() {
        store(1_000L).appendEntries(listOf(entry(1)))
        store(2_000L).appendEntries(listOf(entry(2)))
        val hist = store(3_000L).listHistory()
        assertEquals(2, hist.size)
        // session file names are timestamp-based; the lexicographically smaller name is older
        val oldest = hist.map { it.name }.minOrNull()!!
        assertEquals(listOf(1L), store(9_999L).readHistory(oldest).map { it.id })
    }

    @Test
    fun `enforceRetention keeps newest max files`() {
        // create 5 distinct session files with strictly increasing lastModified
        (1..5).forEach { i ->
            val s = store(i * 10_000L)
            s.appendEntries(listOf(entry(i.toLong())))
        }
        val files = dir.listFiles { f -> f.name.endsWith(EncryptedLogStore.EXT) }!!.sortedBy { it.name }
        assertEquals(5, files.size)
        files.forEachIndexed { idx, f -> f.setLastModified(1_000_000L + idx * 1_000L) }

        val deleted = store(99_000L).enforceRetention(max = 3)
        assertEquals(2, deleted)
        assertEquals(3, dir.listFiles { f -> f.name.endsWith(EncryptedLogStore.EXT) }?.size)
    }

    @Test
    fun `exportToZip produces a zip containing txt`() {
        val s = store(1_000L)
        s.appendEntries(listOf(entry(1, msg = "hello"), entry(2, level = "error", msg = "boom")))
        val out = Files.createTempDirectory("export").toFile()
        val zip = s.exportToZip(out, includeJsonl = true)
        assertTrue(zip.exists() && zip.length() > 0)
        val names = java.util.zip.ZipFile(zip).use { z -> z.entries().toList().map { it.name } }
        assertTrue(names.any { it.endsWith(".txt") })
        assertTrue(names.any { it.endsWith(".jsonl") })
        out.deleteRecursively()
    }

    @Test
    fun `export txt uses system local time while jsonl keeps raw time`() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Shanghai")))
        val s = store(1_000L)
        s.appendEntries(listOf(entry(1, msg = "hello")))
        val out = Files.createTempDirectory("export-time").toFile()
        val zip = s.exportToZip(out, includeJsonl = true)

        java.util.zip.ZipFile(zip).use { z ->
            val txtEntry = z.entries().toList().first { it.name.endsWith(".txt") }
            val jsonEntry = z.entries().toList().first { it.name.endsWith(".jsonl") }
            val txt = z.getInputStream(txtEntry).reader(Charsets.UTF_8).readText()
            val jsonl = z.getInputStream(jsonEntry).reader(Charsets.UTF_8).readText()
            assertTrue(txt.contains("2026-06-24 08:00:01 [INFO] mobilecore(yinghua): hello"))
            assertTrue(jsonl.contains("2026-06-24T00:00:01Z"))
        }

        out.deleteRecursively()
    }
}
