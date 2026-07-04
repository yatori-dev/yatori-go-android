package dev.yatori.mobile.runtime.log

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM [LogCrypto] over a raw 32-byte key. Pure `javax.crypto`, so it runs on both
 * a plain JVM (unit tests, fixed key) and Android (production, Keystore-derived key).
 *
 * On Android the raw key bytes come from a Keystore-protected MasterKey (see the app's
 * key provider); the key material itself is never written to disk by this store.
 */
class AesGcmLogCrypto(keyBytes: ByteArray) : LogCrypto {

    init {
        require(keyBytes.size == 32) { "AES-256 key must be 32 bytes, got ${keyBytes.size}" }
    }

    private val key = SecretKeySpec(keyBytes, "AES")
    private val random = SecureRandom()

    override fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(LogCrypto.IV_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(LogCrypto.GCM_TAG_BITS, iv))
        return iv to cipher.doFinal(plaintext)
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(LogCrypto.GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
