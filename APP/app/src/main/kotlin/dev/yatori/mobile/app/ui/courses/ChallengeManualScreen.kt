package dev.yatori.mobile.app.ui.courses

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.yatori.captcha.OcrEngine
import dev.yatori.mobile.app.di.AppContainer
import dev.yatori.mobile.app.ui.common.ErrorDialog
import dev.yatori.mobile.app.ui.common.SecondaryScaffold
import dev.yatori.mobile.app.ui.nav.Navigator
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Manual captcha entry — only reached when the user taps "手动输入" (or extreme fallback), never
 * automatically. Full-screen (not a bottom sheet) for keyboard control. Back / cancel calls
 * cancelLogin(taskId) to end the flow.
 */
@Composable
fun ChallengeManualScreen(container: AppContainer, nav: Navigator, taskId: String) {
    val scope = rememberCoroutineScope()
    var taskPending by remember {
        mutableStateOf(container.pendingTaskChallengesFlow.value.firstOrNull { it.id == taskId })
    }
    var currentTaskId by remember { mutableStateOf(taskPending?.id ?: taskId) }
    var loginChallenge by remember { mutableStateOf(if (taskPending == null) container.pendingChallenge else null) }
    val challenge = taskPending?.challenge ?: loginChallenge
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }

    val bitmap = remember(challenge) {
        challenge?.imageBase64?.let { OcrEngine.decodeBase64(it) }
    }

    fun cancelAndExit() {
        if (taskPending != null) {
            container.taskChallengeController.clear(currentTaskId)
            container.cancelPendingTaskChallenge(currentTaskId)
        } else {
            scope.launch { container.loginController.cancel(taskId) }
            container.pendingChallenge = null
        }
        nav.pop()
    }

    SecondaryScaffold(
        title = if (taskPending != null) "任务验证码" else "验证码",
        nav = nav,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(top = innerPadding.calculateTopPadding(), bottom = 16.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Card {
                Column(Modifier.padding(16.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    if (bitmap != null) {
                        Image(
                            bitmap.asImageBitmap(),
                            contentDescription = "验证码图片",
                            modifier = Modifier.fillMaxWidth().height(160.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        )
                    } else {
                        Text("无法加载验证码图片", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    Spacer(Modifier.height(16.dp))
                    TextField(value = text, onValueChange = { text = it }, label = "输入图中验证码", singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    submitting = true
                    scope.launch {
                        val typed = text.trim()
                        if (taskPending != null) {
                            val submitTaskId = currentTaskId
                            when (val outcome = container.taskChallengeController.submitManual(submitTaskId, typed)) {
                                is TaskChallengeOutcome.Success -> {
                                    container.completePendingTaskChallenge(submitTaskId, outcome.result)
                                    nav.pop()
                                }
                                is TaskChallengeOutcome.Manual -> {
                                    if (outcome.pending.id != submitTaskId) {
                                        container.cancelPendingTaskChallenge(submitTaskId)
                                    }
                                    container.pendingTaskChallenge = outcome.pending
                                    taskPending = outcome.pending
                                    currentTaskId = outcome.pending.id
                                    text = ""
                                }
                                is TaskChallengeOutcome.Error -> {
                                    error = outcome.message
                                    container.failPendingTaskChallenge(submitTaskId, outcome.message)
                                }
                                is TaskChallengeOutcome.Cancelled -> {
                                    container.cancelPendingTaskChallenge(submitTaskId)
                                    nav.pop()
                                }
                            }
                        } else {
                            when (val outcome = container.loginController.submitManual(taskId, typed)) {
                                is LoginOutcome.Success -> { container.pendingChallenge = null; nav.popToRoot() }
                                is LoginOutcome.Manual -> {
                                    container.pendingChallenge = outcome.challenge
                                    loginChallenge = outcome.challenge
                                    text = ""
                                }
                                is LoginOutcome.Error -> error = outcome.message
                                is LoginOutcome.Cancelled -> nav.pop()
                            }
                        }
                        submitting = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = text.isNotBlank() && !submitting,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(if (submitting) "提交中…" else "提交", color = MiuixTheme.colorScheme.onPrimary) }
        }
    }

    ErrorDialog(show = error != null, title = "验证码错误", message = error ?: "", onDismiss = { error = null })
}
