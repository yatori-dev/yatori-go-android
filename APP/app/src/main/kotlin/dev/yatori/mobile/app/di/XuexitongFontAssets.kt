package dev.yatori.mobile.app.di

import android.content.res.AssetManager
import dev.yatori.mobile.runtime.YatoriCoreRepository

sealed interface XuexitongFontAssetResult {
    data object Injected : XuexitongFontAssetResult
    data object Missing : XuexitongFontAssetResult
    data class Failed(val message: String) : XuexitongFontAssetResult
}

/**
 * Loads host-owned xuexitong anti-scrape font tables and injects them into the Go core.
 *
 * Preferred asset paths:
 * - assets/xuexitong/glyfHashed.json
 * - assets/xuexitong/cmap.json
 *
 * Root-level assets are also accepted for compatibility with ad-hoc test bundles.
 */
class XuexitongFontAssetLoader(
    private val assets: AssetManager,
) {
    suspend fun inject(repository: YatoriCoreRepository): XuexitongFontAssetResult {
        val glyf = readFirst("xuexitong/glyfHashed.json", "glyfHashed.json")
        val cmap = readFirst("xuexitong/cmap.json", "cmap.json")
        if (glyf == null || cmap == null) return XuexitongFontAssetResult.Missing
        return runCatching {
            repository.setXuexitongFontTables(glyf, cmap)
            XuexitongFontAssetResult.Injected
        }.getOrElse { XuexitongFontAssetResult.Failed(it.message ?: it::class.java.simpleName) }
    }

    private fun readFirst(vararg names: String): String? =
        names.firstNotNullOfOrNull { name ->
            runCatching {
                assets.open(name).use { input -> input.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
}
