package dev.yatori.mobile.runtime.log

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Production [LogCrypto] backed by a non-extractable AES-256-GCM key in the Android
 * Keystore. The key material never leaves the Keystore; we encrypt/decrypt via [Cipher]
 * directly rather than handing raw bytes to [AesGcmLogCrypto].
 *
 * Used by the app to construct an [EncryptedLogStore]. Not referenced by JVM unit tests
 * (those use [AesGcmLogCrypto] with a fixed key), so this Android-only class stays out of
 * the test classpath.
 */
class KeystoreLogCrypto(
    private val alias: String = DEFAULT_ALIAS,
) : LogCrypto {

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv // Keystore picks the IV for GCM
        return iv to cipher.doFinal(plaintext)
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(LogCrypto.GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_ALIAS = "yatori_log_key"
    }
}
