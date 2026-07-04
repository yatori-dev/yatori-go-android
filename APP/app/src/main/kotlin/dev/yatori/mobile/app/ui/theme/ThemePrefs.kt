package dev.yatori.mobile.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Dark-mode preference. Default is fixed LIGHT (brief: no default system-dark). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Page-transition style. SLIDE = coordinated horizontal scroll; FADE = crossfade. */
enum class PageAnim { SLIDE, FADE }

/**
 * Predefined accent (key/seed) colors offered in the 强调色 picker, as ARGB ints. `0` means
 * "no custom accent" (default scheme / wallpaper Monet). Mirrors KernelSU's `keyColorOptions`.
 */
val accentColorOptions = listOf(
    0xFFF44336.toInt(), // 红
    0xFFE91E63.toInt(), // 粉
    0xFF9C27B0.toInt(), // 紫
    0xFF673AB7.toInt(), // 深紫
    0xFF3F51B5.toInt(), // 靛蓝
    0xFF2196F3.toInt(), // 蓝
    0xFF00BCD4.toInt(), // 青
    0xFF009688.toInt(), // 蓝绿
    0xFF4CAF50.toInt(), // 绿
    0xFFFFC107.toInt(), // 琥珀
    0xFFFF9800.toInt(), // 橙
    0xFF795548.toInt(), // 棕
    0xFF607D8B.toInt(), // 蓝灰
    0xFFFF9CA8.toInt(), // 樱
)

/** Display names parallel to [accentColorOptions] (same order/length), for the 强调色 dropdown. */
val accentColorNames = listOf(
    "红色", "粉色", "紫色", "深紫色", "靛蓝色", "蓝色", "青色",
    "蓝绿色", "绿色", "琥珀色", "橙色", "棕色", "蓝灰色", "樱色",
)

/** Snapshot of all theme / UI preferences. */
data class ThemeState(
    val mode: ThemeMode = ThemeMode.LIGHT,
    val monet: Boolean = false,           // wallpaper Monet; off by default; Android 12+ only
    val keyColor: Int = 0,                // custom accent seed (ARGB); 0 = none
    val paletteStyle: String = "TonalSpot",   // ThemePaletteStyle name (used when keyColor != 0)
    val colorSpec: String = "Spec2021",       // ThemeColorSpec name (used when keyColor != 0)
    val floatingBar: Boolean = false,
    val blur: Boolean = false,            // requires API 33+
    val liquidGlass: Boolean = false,     // placeholder / pending
    val predictiveBack: Boolean = false,
    val pageAnim: PageAnim = PageAnim.SLIDE,   // secondary push/pop transition
    val tabAnim: PageAnim = PageAnim.FADE,     // bottom-nav tab switch transition
    val uiScale: Float = 1.0f,
)

/**
 * Persists App-level theme / UI settings (NOT Go-core MobileConfig — see blueprint §6/§9).
 * Stored in EncryptedSharedPreferences so nothing user-facing is written in plaintext, even
 * though these values are not strictly sensitive.
 */
class ThemePrefs(context: Context) {

    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            "yatori_theme_prefs",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): ThemeState = ThemeState(
        mode = runCatching { ThemeMode.valueOf(prefs.getString(KEY_MODE, ThemeMode.LIGHT.name)!!) }
            .getOrDefault(ThemeMode.LIGHT),
        monet = prefs.getBoolean(KEY_MONET, false),
        keyColor = prefs.getInt(KEY_KEY_COLOR, 0),
        paletteStyle = prefs.getString(KEY_PALETTE_STYLE, "TonalSpot") ?: "TonalSpot",
        colorSpec = prefs.getString(KEY_COLOR_SPEC, "Spec2021") ?: "Spec2021",
        floatingBar = prefs.getBoolean(KEY_FLOATING, false),
        blur = prefs.getBoolean(KEY_BLUR, false),
        liquidGlass = prefs.getBoolean(KEY_LIQUID, false),
        predictiveBack = prefs.getBoolean(KEY_PREDICTIVE, false),
        pageAnim = runCatching { PageAnim.valueOf(prefs.getString(KEY_PAGE_ANIM, PageAnim.SLIDE.name)!!) }
            .getOrDefault(PageAnim.SLIDE),
        tabAnim = runCatching { PageAnim.valueOf(prefs.getString(KEY_TAB_ANIM, PageAnim.FADE.name)!!) }
            .getOrDefault(PageAnim.FADE),
        uiScale = prefs.getFloat(KEY_SCALE, 1.0f),
    )

    fun save(state: ThemeState) {
        prefs.edit()
            .putString(KEY_MODE, state.mode.name)
            .putBoolean(KEY_MONET, state.monet)
            .putInt(KEY_KEY_COLOR, state.keyColor)
            .putString(KEY_PALETTE_STYLE, state.paletteStyle)
            .putString(KEY_COLOR_SPEC, state.colorSpec)
            .putBoolean(KEY_FLOATING, state.floatingBar)
            .putBoolean(KEY_BLUR, state.blur)
            .putBoolean(KEY_LIQUID, state.liquidGlass)
            .putBoolean(KEY_PREDICTIVE, state.predictiveBack)
            .putString(KEY_PAGE_ANIM, state.pageAnim.name)
            .putString(KEY_TAB_ANIM, state.tabAnim.name)
            .putFloat(KEY_SCALE, state.uiScale)
            .apply()
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_MONET = "monet"
        const val KEY_KEY_COLOR = "key_color"
        const val KEY_PALETTE_STYLE = "palette_style"
        const val KEY_COLOR_SPEC = "color_spec"
        const val KEY_FLOATING = "floating_bar"
        const val KEY_BLUR = "blur"
        const val KEY_LIQUID = "liquid_glass"
        const val KEY_PREDICTIVE = "predictive_back"
        const val KEY_PAGE_ANIM = "page_anim"
        const val KEY_TAB_ANIM = "tab_anim"
        const val KEY_SCALE = "ui_scale"
    }
}
