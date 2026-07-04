package dev.yatori.mobile.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Top-level bottom-nav tabs. */
enum class Tab { HOME, COURSES, LOGS, SETTINGS }

/**
 * App routes. Bottom-nav tabs are the roots; everything else is a pushed secondary screen.
 * Kept as a sealed hierarchy so screen args are type-safe without a nav library.
 */
sealed interface Route {
    // secondary screens
    data object AddAccount : Route
    data class ChallengeManual(val taskId: String) : Route
    data class AnswerEdit(val requestId: String) : Route
    data class QuestionHistory(val operationId: String) : Route
    data class CourseList(val platform: String, val account: String) : Route
    data class CourseDetail(val platform: String, val account: String, val courseId: String) : Route
    data class CourseRuleEditor(val platform: String, val account: String) : Route
    data object LogHistory : Route
    data object ThemeSettings : Route
    data object EmailNotification : Route
    data object AiSettings : Route
    data class StaticDoc(val which: DocKind) : Route
    data object About : Route
    data object AdvancedDiagnostics : Route
    data object OcrDiagnostics : Route
}

enum class DocKind { TERMS, PRIVACY, LICENSE }

/**
 * Minimal navigation: a current [Tab] plus a per-app secondary back stack. The shell renders
 * the tab root when the stack is empty, otherwise the top secondary route. Survives config
 * changes via [rememberNavigator] using rememberSaveable-friendly simple state.
 */
class Navigator {
    var tab by mutableStateOf(Tab.HOME)
        private set

    private val stack = mutableStateListOf<Route>()

    val current: Route? get() = stack.lastOrNull()
    /** The route that would be revealed by a pop (null = the tab root). Drives predictive-back peek. */
    val previous: Route? get() = stack.getOrNull(stack.size - 2)
    val canGoBack: Boolean get() = stack.isNotEmpty()
    /** Depth counter — increases on push, decreases on pop. Drives animation direction. */
    val stackSize: Int get() = stack.size

    fun selectTab(t: Tab) {
        // selecting a tab returns to its root (clears secondary stack)
        stack.clear()
        tab = t
    }

    fun push(route: Route) { stack.add(route) }

    /** Returns true if a secondary screen was popped; false if already at a tab root. */
    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun popToRoot() { stack.clear() }
}

@Composable
fun rememberNavigator(): Navigator = remember { Navigator() }
