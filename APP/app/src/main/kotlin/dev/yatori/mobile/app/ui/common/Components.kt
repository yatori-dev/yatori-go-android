package dev.yatori.mobile.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yatori.mobile.api.dto.LogEntry
import dev.yatori.mobile.api.dto.localFullTime
import dev.yatori.mobile.api.dto.localShortTime
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import top.yukonga.miuix.kmp.window.WindowListPopup
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** Log level → badge color (Android UI renders by level; never relies on Go colorLog). */
fun levelColor(level: String): Color = when (level.lowercase()) {
    "debug" -> Color(0xFF78909C)
    "info" -> Color(0xFF43A047)
    "warn", "warning" -> Color(0xFFFB8C00)
    "error", "crash", "fatal" -> Color(0xFFE53935)
    else -> Color(0xFF90A4AE)
}

/** Small colored level badge, e.g. [E] [W]. [size] lets a caller match it to the adjacent text height. */
@Composable
fun LevelBadge(level: String, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val short = when (level.lowercase()) {
        "debug" -> "D"; "info" -> "I"; "warn", "warning" -> "W"
        "error", "crash", "fatal" -> "E"; else -> "?"
    }
    Box(
        modifier = modifier
            .size(size)
            .background(levelColor(level), RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = short,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.56f).sp,
            lineHeight = (size.value * 0.56f).sp,
        )
    }
}

/** Section header label used above grouped cards. */
@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 6.dp),
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        style = MiuixTheme.textStyles.subtitle,
    )
}

@Composable
fun WarningCard(
    message: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = when {
                isDynamicColor -> MiuixTheme.colorScheme.errorContainer
                dev.yatori.mobile.app.ui.theme.isInDarkTheme() -> Color(0xFF310808)
                else -> Color(0xFFF8E2E2)
            },
        ),
        showIndication = onClick != null,
        pressFeedbackType = PressFeedbackType.Tilt,
        onClick = { onClick?.invoke() },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                color = if (isDynamicColor) MiuixTheme.colorScheme.onErrorContainer else Color(0xFFF72727),
                fontSize = 14.sp,
            )
        }
    }
}

/** Centered empty-state placeholder with an optional action button. */
@Composable
fun EmptyState(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MiuixTheme.textStyles.title3, color = MiuixTheme.colorScheme.onBackground)
        if (subtitle != null) {
            Spacer(Modifier.height(8.dp))
            Text(subtitle, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onBackgroundVariant)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction, colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(actionLabel, color = MiuixTheme.colorScheme.onPrimary)
            }
        }
    }
}

/**
 * Top-bar overflow (⋮) menu: an icon button that opens a Miuix [WindowListPopup] with the
 * given items. Each item is a label + onClick. Used for the "more" menus per the blueprint.
 */
@Composable
fun OverflowMenu(
    items: List<Pair<String, () -> Unit>>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String = "更多",
) {
    var show by remember { mutableStateOf(false) }
    var holdDown by remember { mutableStateOf(false) }
    IconButton(onClick = { show = true; holdDown = true }, holdDownState = holdDown) {
        Icon(icon, contentDescription = contentDescription, tint = MiuixTheme.colorScheme.onBackground)
    }
    WindowListPopup(
        show = show,
        popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
        alignment = PopupPositionProvider.Align.TopEnd,
        onDismissRequest = { show = false },
        onDismissFinished = { holdDown = false },
    ) {
        val dismiss = LocalDismissState.current
        ListPopupColumn {
            items.forEachIndexed { index, (label, action) ->
                DropdownImpl(
                    text = label,
                    optionSize = items.size,
                    isSelected = false,
                    index = index,
                    onSelectedIndexChange = {
                        dismiss?.invoke()
                        action()
                    },
                )
            }
        }
    }
}

/** An entry in a [NestedOverflowMenu]: either a direct action, or a submenu of actions. */
sealed interface MenuEntry {
    val label: String
    data class Action(override val label: String, val onClick: () -> Unit) : MenuEntry
    data class Submenu(
        override val label: String,
        val items: List<Pair<String, () -> Unit>>,
        val selectedIndex: Int? = null,
    ) : MenuEntry
}

/**
 * Two-level overflow (⋮) menu: tapping a [MenuEntry.Submenu] entry closes the top popup and opens
 * a second popup with that submenu's items (a "popup within a popup"). Used by the Logs screen so
 * the level filter lives nested under "日志筛选" instead of cluttering the top level.
 */
@Composable
fun NestedOverflowMenu(
    entries: List<MenuEntry>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String = "更多",
) {
    var showMain by remember { mutableStateOf(false) }
    var submenu by remember { mutableStateOf<MenuEntry.Submenu?>(null) }
    var holdDown by remember { mutableStateOf(false) }

    IconButton(onClick = { showMain = true; holdDown = true }, holdDownState = holdDown) {
        Icon(icon, contentDescription = contentDescription, tint = MiuixTheme.colorScheme.onBackground)
    }

    WindowListPopup(
        show = showMain,
        popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
        alignment = PopupPositionProvider.Align.TopEnd,
        onDismissRequest = { showMain = false },
        onDismissFinished = { if (submenu == null) holdDown = false },
    ) {
        val dismiss = LocalDismissState.current
        ListPopupColumn {
            entries.forEachIndexed { index, entry ->
                DropdownImpl(
                    text = if (entry is MenuEntry.Submenu) "${entry.label}  ›" else entry.label,
                    optionSize = entries.size,
                    isSelected = false,
                    index = index,
                    onSelectedIndexChange = {
                        when (entry) {
                            is MenuEntry.Action -> { dismiss?.invoke(); entry.onClick() }
                            is MenuEntry.Submenu -> { dismiss?.invoke(); submenu = entry }
                        }
                    },
                )
            }
        }
    }

    val sub = submenu
    WindowListPopup(
        show = sub != null,
        popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
        alignment = PopupPositionProvider.Align.TopEnd,
        onDismissRequest = { submenu = null },
        onDismissFinished = { holdDown = false },
    ) {
        val dismiss = LocalDismissState.current
        ListPopupColumn {
            val items = sub?.items.orEmpty()
            items.forEachIndexed { index, (label, action) ->
                DropdownImpl(
                    text = label,
                    optionSize = items.size,
                    isSelected = sub?.selectedIndex == index,
                    index = index,
                    onSelectedIndexChange = { dismiss?.invoke(); action() },
                )
            }
        }
    }
}

/** Confirmation dialog (destructive actions: clear data, delete account, etc). */
@Composable
fun ConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmLabel: String = "确认",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayDialog(show = show, title = title, summary = message, onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
            Button(
                onClick = { onConfirm() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(confirmLabel, color = MiuixTheme.colorScheme.onPrimary) }
        }
    }
}

/** Error dialog with a copyable technical message. */
@Composable
fun ErrorDialog(
    show: Boolean,
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    OverlayDialog(show = show, title = title, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Card(insideMargin = PaddingValues(12.dp)) {
                Text(
                    text = message,
                    fontFamily = FontFamily.Monospace,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(
                    text = "复制",
                    onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(message)) },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("关闭", color = MiuixTheme.colorScheme.onPrimary) }
            }
        }
    }
}

/** Formats a log entry timestamp to MM-dd HH:mm (best-effort; passes through if unparseable). */
fun LogEntry.shortTime(): String = localShortTime()

/** Full timestamp WITH year for log cards: yyyy-MM-dd HH:mm:ss (best-effort). */
fun LogEntry.fullTime(): String = localFullTime()

/**
 * Shared log row (HyperCeiler "view logs" style): a top line with the level badge, source title
 * and a full timestamp, then the message below. Fixed vertical footprint — the message is capped
 * at [messageMaxLines] so one long entry can't dominate the screen; tap to see the full entry.
 */
@Composable
fun LogListItem(
    entry: LogEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    messageMaxLines: Int = 2,
    title: String = entry.platform.orEmpty().ifBlank { "系统/GoCore" },
) {
    Card(modifier = modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp), onClick = onClick) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LevelBadge(entry.level.orEmpty(), size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.fullTime(),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = entry.message.orEmpty(),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = messageMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
