package dev.yatori.mobile.runtime.log

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.yatori.mobile.api.dto.LogEntry
import dev.yatori.mobile.api.dto.localReadableLine
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Metadata for one historical log session file. */
data class LogFileMeta(
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

/**
 * Append-friendly encrypted log session storage.
 *
 * Disk format per file ([dir]/yatori_yyyy-MM-dd_HH-mm-ss.jsonl.enc):
 * a sequence of frames, each:
 * ```
 * [4B big-endian frame length N][1B IV length L][L bytes IV][N bytes GCM ciphertext+tag]
 * ```
 * Each frame's plaintext is the UTF-8 JSON of one [LogEntry] (logical JSONL). Appending a
 * frame never rewrites earlier frames, so this is cheap for high-frequency log writes —
 * unlike whole-file EncryptedFile (which is used for config/session/course cache instead).
 *
 * The plaintext log list is only ever held in memory by the UI; nothing is written to disk
 * unencrypted (export decrypts to a temp file the caller is responsible for cleaning up).
 *
 * A new cold-start session lazily creates its file on the FIRST appended entry, so empty
 * sessions never leave a file behind.
 */
class EncryptedLogStore(
    private val dir: File,
    private val crypto: LogCrypto,
    private val sessionStartMillis: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val gson = GsonBuilder().serializeNulls().create()
    private val entryType = object : TypeToken<LogEntry>() {}.type

    private val sessionFileName: String =
        "yatori_${FILE_TS.format(Date(sessionStartMillis))}$EXT"
    private val sessionFile: File get() = File(dir, sessionFileName)

    // ── append ──────────────────────────────────────────────────────────────────

    /** Appends Go-core / Android log entries to the current session, unchanged and in order. */
    @Synchronized
    fun appendEntries(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        dir.mkdirs()
        java.io.FileOutputStream(sessionFile, /* append = */ true).buffered().use { out ->
            for (entry in entries) {
                val (iv, ciphertext) = crypto.encrypt(gson.toJson(entry).toByteArray(Charsets.UTF_8))
                out.write(intToBytes(ciphertext.size))
                out.write(iv.size)
                out.write(iv)
                out.write(ciphertext)
            }
        }
    }

    // ── read ─────────────────────────────────────────────────────────────────────

    /** Decrypts the full current session, in append order. */
    @Synchronized
    fun readCurrentSession(): List<LogEntry> = readFile(sessionFile)

    /** Decrypts a named historical file. */
    @Synchronized
    fun readHistory(name: String): List<LogEntry> {
        val f = File(dir, name)
        require(f.parentFile == dir) { "history file must live in the log dir" }
        return readFile(f)
    }

    private fun readFile(file: File): List<LogEntry> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<LogEntry>()
        DataInputStream(file.inputStream().buffered()).use { input ->
            while (true) {
                val len = try {
                    input.readInt()
                } catch (e: EOFException) {
                    break
                }
                val ivLen = input.read()
                if (ivLen < 0) break
                val iv = ByteArray(ivLen)
                input.readFully(iv)
                val ciphertext = ByteArray(len)
                input.readFully(ciphertext)
                val plaintext = runCatching { crypto.decrypt(iv, ciphertext) }.getOrNull() ?: continue
                runCatching { gson.fromJson<LogEntry>(String(plaintext, Charsets.UTF_8), entryType) }
                    .getOrNull()?.let { out.add(it) }
            }
        }
        return out
    }

    // ── history / retention ───────────────────────────────────────────────────────

    /** All session files (current + historical), newest first. */
    fun listHistory(): List<LogFileMeta> =
        dir.listFiles { f -> f.name.endsWith(EXT) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { LogFileMeta(it.name, it.length(), it.lastModified()) }
            ?: emptyList()

    /** Clears the current session file. */
    @Synchronized
    fun clearCurrentSession() { sessionFile.delete() }

    fun deleteHistory(name: String): Boolean {
        val f = File(dir, name)
        return f.parentFile == dir && f.delete()
    }

    /** Keeps the newest [max] session files, deletes the rest. Returns count deleted. */
    fun enforceRetention(max: Int = DEFAULT_RETENTION): Int {
        val files = dir.listFiles { f -> f.name.endsWith(EXT) }
            ?.sortedByDescending { it.lastModified() }
            ?: return 0
        if (files.size <= max) return 0
        return files.drop(max).count { it.delete() }
    }

    // ── export ─────────────────────────────────────────────────────────────────────

    /**
     * Decrypts [name] (or current session if null) to a human-readable `.txt` (and optional
     * `.jsonl`), zips into [destDir], and returns the zip File. Caller owns cleanup of the
     * returned file. Nothing unencrypted is left behind beyond the returned zip.
     */
    fun exportToZip(
        destDir: File,
        name: String? = null,
        includeJsonl: Boolean = false,
    ): File {
        val entries = if (name == null) readCurrentSession() else readHistory(name)
        val base = (name ?: sessionFileName).removeSuffix(EXT)
        destDir.mkdirs()
        val zip = File(destDir, "$base.zip")
        java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("$base.txt"))
            zos.write(entries.joinToString("\n") { it.localReadableLine() }.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            if (includeJsonl) {
                zos.putNextEntry(java.util.zip.ZipEntry("$base.jsonl"))
                zos.write(entries.joinToString("\n") { gson.toJson(it) }.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return zip
    }

    private fun intToBytes(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    companion object {
        const val EXT = ".jsonl.enc"
        const val DEFAULT_RETENTION = 30
        private val FILE_TS = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    }
}
