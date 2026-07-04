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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import dev.yatori.mobile.app.di.AppContainer
import dev.yatori.mobile.app.ui.common.ErrorDialog
import dev.yatori.mobile.app.ui.common.SecondaryScaffold
import dev.yatori.mobile.app.ui.common.TopBarAction
import dev.yatori.mobile.app.ui.nav.Navigator
import dev.yatori.mobile.runtime.operation.AnswerEditRequest
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AnswerEditScreen(container: AppContainer, nav: Navigator, requestId: String) {
    val pending by container.pendingAnswerEditsFlow.collectAsState()
    val request = pending.firstOrNull { it.id == requestId }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var refilling by remember { mutableStateOf(false) }
    val answers = remember(requestId) { mutableStateListOf<String>() }
    val isBbs = request?.scope == "bbs"
    val screenTitle = if (isBbs) "内容编辑" else "答案编辑"

    LaunchedEffect(request?.id) {
        answers.clear()
        answers.addAll(request?.suggestedAnswers?.takeIf { it.isNotEmpty() } ?: listOf(""))
    }

    fun cancelAndExit() {
        request?.let { container.operationController.cancel(it.operationId) }
        container.cancelPendingAnswerEdit(requestId)
        nav.pop()
    }

    SecondaryScaffold(
        title = screenTitle,
        nav = nav,
        onBack = { cancelAndExit() },
        actions = {
            TopBarAction(
                icon = Icons.Rounded.AutoAwesome,
                contentDescription = "AI 回填",
                onClick = {
                    val current = request ?: return@TopBarAction
                    refilling = true
                    scope.launch {
                        runCatching { container.refillPendingAnswerWithHostAi(current) }
                            .onSuccess { aiAnswers ->
                                if (aiAnswers.isEmpty()) {
                                    error = "AI 未返回可用答案，请检查 AI 设置或手动填写。"
                                } else {
                                    answers.clear()
                                    answers.addAll(aiAnswers)
                                }
                            }
                            .onFailure { error = it.message ?: "AI 回填失败" }
                        refilling = false
                    }
                },
            )
        },
    ) { innerPadding ->
        if (request == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .padding(top = innerPadding.calculateTopPadding(), bottom = 16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("当前没有待编辑答案", style = MiuixTheme.textStyles.title4, color = MiuixTheme.colorScheme.onSurface)
                Spacer(Modifier.height(12.dp))
                TextButton(text = "返回", onClick = { nav.pop() }, modifier = Modifier.fillMaxWidth())
            }
        } else {
            AnswerEditContent(
                request = request,
                answers = answers,
                refilling = refilling,
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = 16.dp,
                ),
                onAddAnswer = { answers.add("") },
                onRemoveAnswer = { index ->
                    if (answers.size > 1) answers.removeAt(index) else answers[0] = ""
                },
                onAnswerChange = { index, value -> answers[index] = value },
                onRefill = {
                    refilling = true
                    scope.launch {
                        runCatching { container.refillPendingAnswerWithHostAi(request) }
                            .onSuccess { aiAnswers ->
                                if (aiAnswers.isEmpty()) {
                                    error = "AI 未返回可用答案，请检查 AI 设置或手动填写。"
                                } else {
                                    answers.clear()
                                    answers.addAll(aiAnswers)
                                }
                            }
                            .onFailure { error = it.message ?: "AI 回填失败" }
                        refilling = false
                    }
                },
                onSubmit = {
                    val finalAnswers = if (isBbs) {
                        listOf(answers.firstOrNull().orEmpty().trim())
                    } else {
                        answers.map { it.trim() }
                    }
                    if (finalAnswers.all { it.isBlank() }) {
                        error = if (isBbs) "请填写讨论回复内容。" else "请至少填写一个答案。"
                    } else {
                        container.completePendingAnswerEdit(requestId, finalAnswers)
                        nav.pop()
                    }
                },
                onCancel = { cancelAndExit() },
            )
        }
    }

    ErrorDialog(show = error != null, title = screenTitle, message = error.orEmpty(), onDismiss = { error = null })
}

@Composable
private fun AnswerEditContent(
    request: AnswerEditRequest,
    answers: MutableList<String>,
    refilling: Boolean,
    contentPadding: PaddingValues,
    onAddAnswer: () -> Unit,
    onRemoveAnswer: (Int) -> Unit,
    onAnswerChange: (Int, String) -> Unit,
    onRefill: () -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    val isBbs = request.scope == "bbs"
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                Text(request.label, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(8.dp))
                Text(
                    questionContent(request.question).ifBlank { "题干为空" },
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }

        if (request.options.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                    Text("选项", fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(8.dp))
                    request.options.forEachIndexed { index, option ->
                        Text(
                            text = "${('A'.code + index).toChar()}. $option",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    text = if (refilling) "回填中" else "AI 回填",
                    onClick = onRefill,
                    enabled = !refilling,
                    modifier = Modifier.weight(1f),
                )
                if (!isBbs) {
                    TextButton(text = "增加答案", onClick = onAddAnswer, modifier = Modifier.weight(1f))
                }
            }
        }

        itemsIndexed(if (isBbs) answers.take(1) else answers) { index, answer ->
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(14.dp)) {
                if (isBbs) {
                    TextField(
                        value = answer,
                        onValueChange = { onAnswerChange(index, it) },
                        label = "讨论回复内容",
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextField(
                            value = answer,
                            onValueChange = { onAnswerChange(index, it) },
                            label = "答案 ${index + 1}",
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(text = "删除", onClick = { onRemoveAnswer(index) })
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(text = "取消运行", onClick = onCancel, modifier = Modifier.weight(1f))
                Button(
                    onClick = onSubmit,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(if (isBbs) "提交内容" else "提交答案", color = MiuixTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

private fun questionContent(question: JsonObject): String =
    question.string("content").ifBlank { question.string("prompt") }.ifBlank { question.string("title") }

private fun JsonObject.string(key: String): String =
    runCatching { get(key)?.asString ?: "" }.getOrDefault("")
