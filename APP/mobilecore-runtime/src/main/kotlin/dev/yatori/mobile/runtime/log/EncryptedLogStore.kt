package dev.yatori.mobile.runtime.log

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.yatori.mobile.api.dto.LogEntry
import dev.yatori.mobile.api.dto.localReadableLine
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    // ── live in-memory buffer (display source of truth) ──────────────────────────
    //
    // The UI reads logs from this bounded, newest-last list — NEVER by decrypting the whole
    // file on a timer. Every append (Go-core poll OR runtime event) funnels through
    // [appendEntries], so the buffer always reflects the current session. The encrypted file
    // remains the durable/export copy.

    private val liveState = MutableStateFlow<List<LogEntry>>(emptyList())
    /** Bounded (≤[LIVE_CAP]) current-session log list, append order, pushed on every write. */
    val live: StateFlow<List<LogEntry>> = liveState.asStateFlow()

    private var seeded = false
    /** Approx. number of frames currently on disk for this session (for compaction). */
    private var diskFrameCount = 0

    // ── append ──────────────────────────────────────────────────────────────────

    /** Appends Go-core / Android log entries to the current session, unchanged and in order. */
    @Synchronized
    fun appendEntries(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        seedIfNeeded()
        dir.mkdirs()
        java.io.FileOutputStream(sessionFile, /* append = */ true).buffered().use { out ->
            for (entry in entries) writeFrame(out, entry)
        }
        diskFrameCount += entries.size
        val merged = (liveState.value + entries).distinctBy { it.source to it.id }
        val ordered = stableChronologicalOrder(merged)
        liveState.value = if (ordered.size > LIVE_CAP) ordered.takeLast(LIVE_CAP) else ordered
        if (diskFrameCount > FILE_COMPACT_THRESHOLD) compactCurrentSession()
    }

    private fun writeFrame(out: OutputStream, entry: LogEntry) {
        val (iv, ciphertext) = crypto.encrypt(gson.toJson(entry).toByteArray(Charsets.UTF_8))
        out.write(intToBytes(ciphertext.size))
        out.write(iv.size)
        out.write(iv)
        out.write(ciphertext)
    }

    /**
     * Populates the live buffer from the on-disk session once (lazily). Full-file decrypt, but
     * paid a single time per process — not on every UI refresh. Safe to call repeatedly.
     */
    @Synchronized
    fun seedIfNeeded() {
        if (seeded) return
        seeded = true
        val all = stableChronologicalOrder(readFile(sessionFile).distinctBy { it.source to it.id })
        diskFrameCount = all.size
        liveState.value = if (all.size > LIVE_CAP) all.takeLast(LIVE_CAP) else all
    }

    /**
     * Caps the current-session file: rewrites it to contain only the live buffer (newest
     * ≤[LIVE_CAP]). Called when the file crosses [FILE_COMPACT_THRESHOLD] frames, so the session
     * file never grows without bound. Trade-off: current-session export is limited to the newest
     * frames kept after the last compaction.
     */
    @Synchronized
    private fun compactCurrentSession() {
        val snapshot = liveState.value
        if (snapshot.isEmpty()) return
        dir.mkdirs()
        val tmp = File(dir, "$sessionFileName.compact.tmp")
        try {
            java.io.FileOutputStream(tmp).buffered().use { out ->
                for (entry in snapshot) writeFrame(out, entry)
            }
            if (tmp.renameTo(sessionFile)) {
                diskFrameCount = snapshot.size
            } else if (sessionFile.delete() && tmp.renameTo(sessionFile)) {
                diskFrameCount = snapshot.size
            } else {
                tmp.delete()
            }
        } catch (_: Exception) {
            tmp.delete()
        }
    }


    // ── read ─────────────────────────────────────────────────────────────────────

    /** Decrypts and chronologically orders the full current session. */
    @Synchronized
    fun readCurrentSession(): List<LogEntry> =
        stableChronologicalOrder(readFile(sessionFile).distinctBy { it.source to it.id })

    /** Decrypts and chronologically orders a named historical file. */
    @Synchronized
    fun readHistory(name: String): List<LogEntry> {
        val f = File(dir, name)
        require(f.parentFile == dir) { "history file must live in the log dir" }
        return stableChronologicalOrder(readFile(f).distinctBy { it.source to it.id })
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
    fun clearCurrentSession() {
        sessionFile.delete()
        liveState.value = emptyList()
        diskFrameCount = 0
        seeded = true
    }

    /** Clears every current and historical session file. Returns the number deleted. */
    @Synchronized
    fun clearHistory(): Int {
        val files = dir.listFiles { f -> f.name.endsWith(EXT) }.orEmpty()
        val deleted = files.count { it.delete() }
        liveState.value = emptyList()
        diskFrameCount = 0
        seeded = true
        return deleted
    }

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

        /** Max entries kept in the live in-memory buffer (and thus shown in the UI). */
        const val LIVE_CAP = 2000

        /** When the session file exceeds this many frames, it is compacted down to [LIVE_CAP]. */
        const val FILE_COMPACT_THRESHOLD = 6000

        private val FILE_TS = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    }
}
