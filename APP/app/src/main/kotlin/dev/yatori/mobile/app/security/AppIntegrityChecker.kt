package dev.yatori.mobile.app.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * Release-only package/signature guard. Debug builds skip this because their signing identity
 * differs from the release keystore.
 */
object AppIntegrityChecker {
    private const val EXPECTED_SHA256 = "6013B61801E2FBD37A4A163D974C12035C7D04B755AADDD5A4C720E9D79BA4C0"
    private const val EXPECTED_PACKAGE = "com.yatori.go.android"

    fun check(ctx: Context): Boolean {
        if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return true
        if (ctx.packageName != EXPECTED_PACKAGE) {
            Log.w(TAG, "Package name mismatch: ${ctx.packageName}")
            return false
        }
        val signatures = runCatching { loadSignatures(ctx) }.getOrElse {
            Log.w(TAG, "Unable to load signing certificate", it)
            return false
        }
        val matched = signatures.any { sha256(it.toByteArray()) == EXPECTED_SHA256 }
        if (!matched) Log.w(TAG, "Signature mismatch")
        return matched
    }

    private fun loadSignatures(ctx: Context): List<android.content.pm.Signature> {
        val pm = ctx.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return emptyList()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners.toList()
            } else {
                signingInfo.signingCertificateHistory?.toList().orEmpty()
            }
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.toList().orEmpty()
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02X".format(it) }
    }

    private const val TAG = "AppIntegrity"
}
