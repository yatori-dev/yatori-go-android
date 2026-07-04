package dev.yatori.mobile.runtime.log

/**
 * Per-frame AES-GCM crypto used by [EncryptedLogStore].
 *
 * Abstracted so the framing/append/read logic can be unit-tested on a plain JVM with a
 * fixed in-memory key, while the production app uses an Android Keystore-backed key.
 *
 * Each call uses a fresh random 12-byte IV; [encrypt] returns IV-prefixed output is NOT
 * assumed — the store stores the IV in the frame header explicitly. Implementations must
 * therefore expose the IV separately.
 */
interface LogCrypto {
    /** Encrypts [plaintext] with a fresh IV. Returns (iv, ciphertextWithTag). */
    fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray>

    /** Decrypts ciphertext produced by [encrypt] given its [iv]. */
    fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray

    companion object {
        const val IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
    }
}
