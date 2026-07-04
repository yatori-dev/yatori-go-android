package dev.yatori.mobile.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import dev.yatori.mobile.app.di.AppContainer
import dev.yatori.mobile.app.ui.YatoriApp
import dev.yatori.mobile.app.ui.theme.LocalIsDark
import dev.yatori.mobile.app.ui.theme.LocalThemeState
import dev.yatori.mobile.app.ui.theme.ThemeState
import dev.yatori.mobile.app.ui.theme.YatoriTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val container = AppContainer.from(this)

        setContent {
            // Theme state is owned here so changes in ThemeSettings re-apply immediately and
            // drive both MiuixTheme and the system-bar icon colors (edge-to-edge).
            var themeState by remember { mutableStateOf(container.themePrefs.load()) }

            YatoriTheme(state = themeState) { isDark ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { }
                    LaunchedEffect(Unit) {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                // KernelSU edge-to-edge pattern: transparent bars, icon colors follow dark mode,
                // disable navigation-bar contrast enforcement so no black scrim appears.
                DisposableEffect(isDark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { isDark },
                        navigationBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT) { isDark },
                    )
                    window.isNavigationBarContrastEnforced = false
                    onDispose { }
                }

                // Miuix's popup host (OverlayDialog / WindowListPopup) requires a
                // NavigationEventDispatcherOwner in the tree; provide a root one (parent = null).
                CompositionLocalProvider(
                    LocalNavigationEventDispatcherOwner provides rememberNavigationEventDispatcherOwner(parent = null),
                    LocalIsDark provides isDark,
                    LocalThemeState provides themeState,
                ) {
                    YatoriApp(
                        container = container,
                        themeState = themeState,
                        onThemeChange = { newState: ThemeState ->
                            themeState = newState
                            container.themePrefs.save(newState)
                        },
                    )
                }
            }
        }
    }

    private companion object {
        const val TRANSPARENT = android.graphics.Color.TRANSPARENT
    }
}
