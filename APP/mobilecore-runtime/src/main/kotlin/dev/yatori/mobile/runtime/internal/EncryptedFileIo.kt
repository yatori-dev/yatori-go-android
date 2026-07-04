package dev.yatori.mobile.runtime.internal

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Production [SecureFileIo] backed by Jetpack Security [EncryptedFile] (AES-256-GCM,
 * key stored in the Android Keystore via [MasterKey]).
 *
 * Whole-file read/write only — used for the small config / session / course-cache JSON
 * blobs. High-frequency log appends use the framed EncryptedLogStore instead, never this.
 *
 * EncryptedFile refuses to open an existing file for writing, so [write] writes to a
 * temp sibling and atomically renames over the target.
 */
class EncryptedFileIo(context: Context) : SecureFileIo {

    private val appContext = context.applicationContext
    private val masterKey: MasterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private fun encryptedFile(file: File): EncryptedFile =
        EncryptedFile.Builder(
            appContext,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    /**
     * EncryptedFile (Tink StreamingAead) binds the ciphertext to the file PATH as associated
     * data, so a temp-file-then-rename corrupts it ("No matching key found"). We therefore
     * encrypt directly to the final path. EncryptedFile refuses to open an existing file for
     * writing, so we delete first; the small window without a file is acceptable for these
     * config/session/cache blobs (logs use the append-only EncryptedLogStore instead).
     */
    override fun write(file: File, content: String) {
        file.parentFile?.mkdirs()
        if (file.exists()) file.delete()
        encryptedFile(file).openFileOutput().use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    override fun read(file: File): String? {
        if (!file.exists()) return null
        // A decrypt failure (corrupt/legacy/interrupted write) degrades to null rather than
        // crashing the caller; the blob is simply treated as absent and re-created on next write.
        return runCatching {
            encryptedFile(file).openFileInput().use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    override fun delete(file: File): Boolean = file.exists() && file.delete()
}
