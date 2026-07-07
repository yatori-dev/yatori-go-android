package dev.yatori.mobile.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.yatori.mobile.app.di.AppContainer
import dev.yatori.mobile.app.ui.component.FloatingBottomBar
import dev.yatori.mobile.app.ui.component.FloatingBottomBarItem
import dev.yatori.mobile.app.ui.courses.AddAccountScreen
import dev.yatori.mobile.app.ui.courses.AnswerEditScreen
import dev.yatori.mobile.app.ui.courses.ChallengeManualScreen
import dev.yatori.mobile.app.ui.courses.CourseDetailScreen
import dev.yatori.mobile.app.ui.courses.CourseListScreen
import dev.yatori.mobile.app.ui.courses.CourseRuleEditorScreen
import dev.yatori.mobile.app.ui.courses.CoursesScreen
import dev.yatori.mobile.app.ui.courses.QuestionHistoryScreen
import dev.yatori.mobile.app.ui.home.HomeScreen
import dev.yatori.mobile.app.ui.logs.LogHistoryDetailScreen
import dev.yatori.mobile.app.ui.logs.LogHistoryScreen
import dev.yatori.mobile.app.ui.logs.LogsScreen
import dev.yatori.mobile.app.ui.nav.Route
import dev.yatori.mobile.app.ui.nav.Tab
import dev.yatori.mobile.app.ui.nav.rememberNavigator
import dev.yatori.mobile.app.ui.settings.AboutScreen
import dev.yatori.mobile.app.ui.settings.AdvancedDiagnosticsScreen
import dev.yatori.mobile.app.ui.settings.AiSettingsScreen
import dev.yatori.mobile.app.ui.settings.EmailNotificationScreen
import dev.yatori.mobile.app.ui.settings.OcrDiagnosticsScreen
import dev.yatori.mobile.app.ui.settings.SettingsScreen
import dev.yatori.mobile.app.ui.settings.StaticDocScreen
import dev.yatori.mobile.app.ui.settings.ThemeSettingsScreen
import dev.yatori.mobile.app.ui.theme.PageAnim
import dev.yatori.mobile.app.ui.theme.ThemeState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.coroutines.cancellation.CancellationException

@Composable
fun YatoriApp(
    container: AppContainer,
    themeState: ThemeState,
    onThemeChange: (ThemeState) -> Unit,
) {
    val nav = rememberNavigator()

    LaunchedEffect(container) {
        container.updateController.checkOnStartup()
    }

    val tabContent = remember(container, nav) {
        mapOf<Tab, @Composable (Dp, Boolean) -> Unit>(
            Tab.HOME     to { bp, active -> HomeScreen(container, nav, bp, active) },
            Tab.COURSES  to { bp, active -> CoursesScreen(container, nav, bp, active) },
            Tab.LOGS     to { bp, active -> LogsScreen(container, nav, bp, active) },
            Tab.SETTINGS to { bp, _ -> SettingsScreen(container, nav, bp) },
        )
    }

    val showSecondary = nav.current != null
    val useFloating = themeState.floatingBar

    // Liquid-glass backdrop captures the tab content so the floating bar can refract it.
    val surfaceColor = MiuixTheme.colorScheme.surface
    val contentBackdrop = rememberLayerBackdrop { drawRect(surfaceColor); drawContent() }
    val blurActive = useFloating && !showSecondary && themeState.blur && isRenderEffectSupported()

    Scaffold(
        bottomBar = {
            // Conditionally rendered (NOT alpha=0) — secondary route → empty slot → zero layout/touch area.
            if (!showSecondary) {
                if (useFloating) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                        contentAlignment = Alignment.Center,
                    ) {
                        FloatingBottomBar(
                            selectedIndex = { nav.tab.ordinal },
                            onSelected = { idx -> nav.selectTab(Tab.entries[idx]) },
                            backdrop = contentBackdrop,
                            tabsCount = Tab.entries.size,
                            isBlurEnabled = blurActive,
                            liquidGlass = themeState.liquidGlass,
                        ) {
                            FloatingTab(Icons.Rounded.Cottage, "主页") { nav.selectTab(Tab.HOME) }
                            FloatingTab(Icons.Rounded.MenuBook, "课程") { nav.selectTab(Tab.COURSES) }
                            FloatingTab(Icons.Rounded.Description, "日志") { nav.selectTab(Tab.LOGS) }
                            FloatingTab(Icons.Rounded.Settings, "设置") { nav.selectTab(Tab.SETTINGS) }
                        }
                    }
                } else {
                    NavigationBar {
                        NavigationBarItem(selected = nav.tab == Tab.HOME,     onClick = { nav.selectTab(Tab.HOME) },     icon = Icons.Rounded.Cottage,     label = "主页")
                        NavigationBarItem(selected = nav.tab == Tab.COURSES,  onClick = { nav.selectTab(Tab.COURSES) },  icon = Icons.Rounded.MenuBook,    label = "课程")
                        NavigationBarItem(selected = nav.tab == Tab.LOGS,     onClick = { nav.selectTab(Tab.LOGS) },     icon = Icons.Rounded.Description, label = "日志")
                        NavigationBarItem(selected = nav.tab == Tab.SETTINGS, onClick = { nav.selectTab(Tab.SETTINGS) }, icon = Icons.Rounded.Settings,    label = "设置")
                    }
                }
            }
        },
    ) { innerPadding ->
        val bp = innerPadding.calculateBottomPadding()

        val sysDensity = LocalDensity.current
        val scaledDensity = remember(sysDensity, themeState.uiScale) {
            if (themeState.uiScale == 1.0f) sysDensity
            else Density(sysDensity.density * themeState.uiScale, sysDensity.fontScale)
        }

        CompositionLocalProvider(LocalDensity provides scaledDensity) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .let { if (blurActive) it.layerBackdrop(contentBackdrop) else it },
            ) {
                val animState by remember { derivedStateOf { nav.current to nav.stackSize } }
                val transitionState = remember { SeekableTransitionState(animState) }

                LaunchedEffect(animState) {
                    if (transitionState.currentState != animState) transitionState.animateTo(animState)
                }

                if (nav.canGoBack && themeState.predictiveBack) {
                    PredictiveBackHandler(enabled = true) { progress ->
                        val target = nav.previous to (nav.stackSize - 1)
                        try {
                            progress.collect { event -> transitionState.seekTo(event.progress, target) }
                            nav.pop()
                        } catch (_: CancellationException) {
                            transitionState.snapTo(animState)
                        }
                    }
                } else {
                    BackHandler(enabled = nav.canGoBack) { nav.pop() }
                }

                // Layer 1: Tabs are ALWAYS in composition — never unmounted.
                // This prevents the ~1 s cold-start delay when returning from a secondary
                // screen (previously the tab branch was inside AnimatedContent and had to be
                // composed from scratch on every back-navigation).
                Box(modifier = Modifier.fillMaxSize()) {
                    Tab.entries.forEach { tab ->
                        val isActive = tab == nav.tab
                        val alpha = if (themeState.tabAnim == PageAnim.FADE) {
                            animateFloatAsState(
                                targetValue = if (isActive) 1f else 0f,
                                animationSpec = tween(150),
                                label = "tab_alpha_${tab.name}",
                            ).value
                        } else {
                            if (isActive) 1f else 0f
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(if (isActive) 1f else 0f)
                                .graphicsLayer { this.alpha = alpha },
                        ) {
                            tabContent[tab]?.invoke(bp, isActive)
                        }
                    }
                }

                // Layer 2: Secondary screens rendered on top as an overlay.
                // When route == null the content is transparent; the tabs below show through.
                // This means back-navigation only needs to animate the secondary screen OUT —
                // the tab content is already fully composed and visible underneath.
                // SaveableStateHolder: preserves rememberSaveable state (scroll positions, etc.)
                // per route across AnimatedContent composition disposal/re-entry.
                val saveableStateHolder = rememberSaveableStateHolder()
                // When a route is truly popped (nav.current goes from that route to null),
                // clear its saved state so the next entry starts fresh.
                val prevRoute = remember { mutableStateOf<Route?>(nav.current) }
                LaunchedEffect(nav.current) {
                    val prev = prevRoute.value
                    prevRoute.value = nav.current
                    if (prev != null && nav.current == null) {
                        saveableStateHolder.removeState(prev.toString())
                    }
                }
                val transition = rememberTransition(transitionState, label = "route")
                transition.AnimatedContent(
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        val push = targetState.second > initialState.second
                        when (themeState.pageAnim) {
                            PageAnim.FADE ->
                                (fadeIn(tween(220)) togetherWith fadeOut(tween(220))).using(null)
                            PageAnim.SLIDE -> if (push)
                                ContentTransform(
                                    targetContentEnter = slideInHorizontally(tween(300)) { it },
                                    initialContentExit = slideOutHorizontally(tween(300)) { -it / 4 },
                                    targetContentZIndex = 1f,
                                    sizeTransform = null,
                                )
                            else
                                ContentTransform(
                                    targetContentEnter = slideInHorizontally(tween(300)) { -it / 4 },
                                    initialContentExit = slideOutHorizontally(tween(300)) { it },
                                    targetContentZIndex = -1f,
                                    sizeTransform = null,
                                )
                        }
                    },
                    contentAlignment = Alignment.TopStart,
                ) { (route, _) ->
                    if (route != null) {
                        saveableStateHolder.SaveableStateProvider(route.toString()) {
                            when (route) {
                                is Route.AddAccount       -> AddAccountScreen(container, nav)
                                is Route.AnswerEdit       -> AnswerEditScreen(container, nav, route.requestId)
                                is Route.ChallengeManual  -> ChallengeManualScreen(container, nav, route.taskId)
                                is Route.CourseList       -> CourseListScreen(container, nav, route.platform, route.account)
                                is Route.CourseDetail     -> CourseDetailScreen(container, nav, route.platform, route.account, route.courseId)
                                is Route.CourseRuleEditor -> CourseRuleEditorScreen(container, nav, route.platform, route.account)
                                is Route.QuestionHistory  -> QuestionHistoryScreen(container, nav, route.operationId)
                                is Route.LogHistory       -> LogHistoryScreen(container, nav)
                                is Route.LogHistoryDetail -> LogHistoryDetailScreen(container, nav, route.fileName)
                                is Route.ThemeSettings    -> ThemeSettingsScreen(nav, themeState, onThemeChange)
                                is Route.EmailNotification -> EmailNotificationScreen(container, nav)
                                is Route.AiSettings       -> AiSettingsScreen(container, nav)
                                is Route.StaticDoc        -> StaticDocScreen(nav, route.which)
                                is Route.About            -> AboutScreen(container, nav)
                                is Route.AdvancedDiagnostics -> AdvancedDiagnosticsScreen(container, nav)
                                is Route.OcrDiagnostics   -> OcrDiagnosticsScreen(container, nav)
                            }
                        }
                    }
                    // else: route == null → render nothing; tabs in Layer 1 show through.
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.FloatingTab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    FloatingBottomBarItem(onClick = onClick, modifier = Modifier.defaultMinSize(minWidth = 72.dp)) {
        Icon(icon, contentDescription = label, tint = MiuixTheme.colorScheme.onSurface)
        Text(label, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurface, maxLines = 1)
    }
}
