package dev.yatori.mobile.app.ui.courses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.yatori.mobile.app.di.AppContainer
import dev.yatori.mobile.app.ui.common.SecondaryScaffold
import dev.yatori.mobile.app.ui.nav.Navigator
import dev.yatori.mobile.runtime.operation.QuestionHistoryEntry
import dev.yatori.mobile.runtime.operation.QuestionHistoryStatus
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun QuestionHistoryScreen(container: AppContainer, nav: Navigator, operationId: String) {
    val history by container.operationController.questionHistory.collectAsState()
    val entries = history.filter { it.operationId == operationId }

    SecondaryScaffold(title = "题目历史", nav = nav) { innerPadding ->
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .padding(top = innerPadding.calculateTopPadding(), bottom = 16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("暂无题目历史", style = MiuixTheme.textStyles.title4, color = MiuixTheme.colorScheme.onSurface)
                Spacer(Modifier.height(12.dp))
                TextButton(text = "返回", onClick = { nav.pop() }, modifier = Modifier.fillMaxWidth())
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { QuestionHistorySummary(entries) }
                items(entries, key = { it.id }) { entry ->
                    QuestionHistoryCard(entry)
                }
            }
        }
    }
}

@Composable
private fun QuestionHistorySummary(entries: List<QuestionHistoryEntry>) {
    val answered = entries.count { it.status == QuestionHistoryStatus.ANSWERED }
    val saved = entries.count { it.status == QuestionHistoryStatus.SAVED }
    val submitted = entries.count { it.status == QuestionHistoryStatus.SUBMITTED }
    val waiting = entries.count { it.status == QuestionHistoryStatus.WAITING_EDIT }
    val failed = entries.count { it.status == QuestionHistoryStatus.FAILED || it.status == QuestionHistoryStatus.MISSING }
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
        Text("共 ${entries.size} 题", fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(
            "已答 $answered / 已保存 $saved / 已提交 $submitted / 等待 $waiting / 异常 $failed",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun QuestionHistoryCard(entry: QuestionHistoryEntry) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(
                    entryTitle(entry),
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entrySubtitle(entry),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                statusText(entry.status),
                color = statusColor(entry.status),
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        if (entry.contentPreview.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                entry.contentPreview,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.answerPreview.isNotBlank() || entry.answerSource.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "答案来源：${entry.answerSource.ifBlank { "-" }} · ${entry.answerPreview.ifBlank { "-" }}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.finalSubmit || entry.realSubmit || entry.message.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                answerSubmitText(entry),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun entryTitle(entry: QuestionHistoryEntry): String {
    val index = if (entry.questionIndex > 0) {
        if (entry.questionTotal > 0) "${entry.questionIndex}/${entry.questionTotal}" else entry.questionIndex.toString()
    } else {
        "-"
    }
    return "${entry.scope} $index ${entry.questionId}".trim()
}

private fun entrySubtitle(entry: QuestionHistoryEntry): String =
    listOf(entry.taskId, entry.questionType, entry.label)
        .filter { it.isNotBlank() }
        .joinToString(" · ")

private fun statusText(status: QuestionHistoryStatus): String = when (status) {
    QuestionHistoryStatus.PENDING -> "读取中"
    QuestionHistoryStatus.ANSWERED -> "已答"
    QuestionHistoryStatus.WAITING_EDIT -> "等待编辑"
    QuestionHistoryStatus.MISSING -> "没答案"
    QuestionHistoryStatus.SAVED -> "已保存"
    QuestionHistoryStatus.SUBMITTED -> "已提交"
    QuestionHistoryStatus.SKIPPED -> "跳过"
    QuestionHistoryStatus.FAILED -> "失败"
}

private fun answerSubmitText(entry: QuestionHistoryEntry): String = buildString {
    append(
        when {
            entry.status == QuestionHistoryStatus.SUBMITTED && entry.realSubmit -> "答案已提交"
            entry.status == QuestionHistoryStatus.SAVED || !entry.realSubmit -> "答案未提交，已自动保存"
            entry.finalSubmit -> "准备交卷"
            else -> "答案已保存"
        },
    )
    val message = resultText(entry.message)
    if (message.isNotBlank()) append(" · ").append(message)
}

private fun resultText(raw: String): String = when (raw.trim()) {
    "", "-" -> ""
    "dry_run" -> "未提交"
    "saved" -> "已保存"
    "submitted", "done" -> "已提交"
    "captcha" -> "需要验证码"
    else -> raw
}

@Composable
private fun statusColor(status: QuestionHistoryStatus): Color = when (status) {
    QuestionHistoryStatus.SUBMITTED,
    QuestionHistoryStatus.SAVED,
    QuestionHistoryStatus.ANSWERED -> MiuixTheme.colorScheme.primary
    QuestionHistoryStatus.WAITING_EDIT,
    QuestionHistoryStatus.PENDING,
    QuestionHistoryStatus.SKIPPED -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    QuestionHistoryStatus.MISSING,
    QuestionHistoryStatus.FAILED -> Color(0xFFE53935)
}
