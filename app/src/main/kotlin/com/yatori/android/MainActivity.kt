package com.yatori.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.yatori.android.data.datastore.SettingsDataStore
import com.yatori.android.ui.navigation.Screen
import com.yatori.android.ui.navigation.YatoriNavGraph
import com.yatori.android.ui.theme.YatoriTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsStore: SettingsDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 防止截图和录屏泄露敏感内容
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()

        lifecycleScope.launch {
            val onboarded = settingsStore.onboarded.first()
            val start = if (onboarded) Screen.Home.route else Screen.Onboarding.route
            setContent {
                val settings by settingsStore.settings.collectAsStateWithLifecycle(
                    initialValue = com.yatori.android.domain.model.AppSettings()
                )
                val systemDark = isSystemInDarkTheme()
                val useDark = if (settings.followSystem) systemDark else settings.darkMode

                // 运行时通知权限申请（Android 13+）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val launcher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { }
                    LaunchedEffect(Unit) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                YatoriTheme(darkTheme = useDark) {
                    YatoriNavGraph(startDestination = start)
                }
            }
        }
    }
}
