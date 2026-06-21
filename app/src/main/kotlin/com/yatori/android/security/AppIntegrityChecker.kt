package com.yatori.android.security

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.security.MessageDigest

/**
 * 签名证书 + 包名完整性校验。
 * release 包中校验失败（二次打包）返回 false，由入口处强制退出；
 * debug 包自动跳过（debug.keystore 指纹与 release 不同）。
 */
object AppIntegrityChecker {

    // 正式签名 SHA-256（用 keytool -list -v 查询得出）
    private const val EXPECTED_SHA256 =
        "6013B61801E2FBD37A4A163D974C12035C7D04B755AADDD5A4C720E9D79BA4C0"

    private const val EXPECTED_PACKAGE = "com.yatori.android"

    fun check(ctx: Context): Boolean {
        // debug 构建跳过签名校验（debug.keystore 指纹与 release 不同）
        if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            return true
        }
        if (!checkPackageName(ctx)) {
            Log.w("Security", "Package name mismatch")
            return false
        }
        if (!checkSignature(ctx)) {
            Log.w("Security", "Signature mismatch")
            return false
        }
        return true
    }

    private fun checkPackageName(ctx: Context) = ctx.packageName == EXPECTED_PACKAGE

    private fun checkSignature(ctx: Context): Boolean {
        return try {
            @Suppress("DEPRECATION")
            val sig = ctx.packageManager
                .getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
                .signatures?.firstOrNull() ?: return false
            val md = MessageDigest.getInstance("SHA-256")
            val fingerprint = md.digest(sig.toByteArray())
                .joinToString("") { "%02X".format(it) }
            fingerprint == EXPECTED_SHA256
        } catch (e: Exception) {
            false
        }
    }
}
