package dev.yatori.mobile.runtime.internal

import java.io.File

/**
 * File read/write abstraction so [dev.yatori.mobile.runtime.MobilecoreStore] can be
 * unit-tested on a plain JVM (where Android's EncryptedFile is unavailable) while the
 * production app uses real Keystore-backed encryption.
 *
 * Implementations:
 *  - [PlaintextFileIo]: atomic plaintext writes; used by JVM unit tests only.
 *  - `EncryptedFileIo` (app/Android side): Jetpack Security EncryptedFile, used in production.
 *
 * All paths are whole-file read/write (config / session / course cache are small JSON blobs).
 * Logs do NOT go through this — they use the append-friendly EncryptedLogStore.
 */
interface SecureFileIo {
    /** Whole-file write. Must be atomic-ish (write temp then replace) where possible. */
    fun write(file: File, content: String)

    /** Whole-file read, or null if the file does not exist. */
    fun read(file: File): String?

    fun exists(file: File): Boolean = file.exists()

    fun delete(file: File): Boolean
}

/**
 * Plaintext atomic file IO. Intended for JVM unit tests; the production app injects an
 * EncryptedFile-backed implementation instead. Kept internal to the runtime module.
 */
internal class PlaintextFileIo : SecureFileIo {
    override fun write(file: File, content: String) = JsonFileStore.write(file, content)
    override fun read(file: File): String? = JsonFileStore.read(file)
    override fun delete(file: File): Boolean = JsonFileStore.delete(file)
}
