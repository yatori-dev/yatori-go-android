package com.yatori.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Home : Screen("home")
    object Accounts : Screen("accounts")
    object AccountEdit : Screen("account_edit?id={id}") {
        fun withId(id: Long?) = "account_edit?id=${id ?: -1}"
    }
    object Tasks : Screen("tasks")
    object Logs : Screen("logs")
    object Settings : Screen("settings")
    object About : Screen("about")
}

data class NavItem(val screen: Screen, val label: String, val icon: ImageVector)

val bottomNavItems = listOf(
    NavItem(Screen.Home, "首页", Icons.Default.Home),
    NavItem(Screen.Accounts, "账号", Icons.Default.AccountCircle),
    NavItem(Screen.Logs, "日志", Icons.Default.List),
    NavItem(Screen.Settings, "设置", Icons.Default.Settings),
    NavItem(Screen.About, "关于", Icons.Default.Info)
)
