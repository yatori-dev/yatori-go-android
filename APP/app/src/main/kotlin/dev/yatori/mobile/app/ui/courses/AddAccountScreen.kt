package dev.yatori.mobile.app.ui.courses

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import dev.yatori.mobile.app.di.AppContainer
import dev.yatori.mobile.app.platform.PlatformMeta
import dev.yatori.mobile.app.platform.allPlatformMeta
import dev.yatori.mobile.app.ui.common.ErrorDialog
import dev.yatori.mobile.app.ui.common.SecondaryScaffold
import dev.yatori.mobile.app.ui.nav.Navigator
import dev.yatori.mobile.app.ui.nav.Route
import dev.yatori.mobile.api.dto.AccountInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Add-account screen. Fields are platform-metadata driven (URL only for yinghua/haiqikeji).
 * Submitting starts the background auto-OCR login loop; the UI shows "识别中（第N次）…" with
 * 取消 / 手动输入 instead of forcing manual captcha entry.
 */
@Composable
fun AddAccountScreen(container: AppContainer, nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loginController = container.loginController

    var platformIndex by rememberSaveable { mutableStateOf(0) }
    val meta: PlatformMeta = allPlatformMeta[platformIndex]
    var account by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }

    var attempt by remember { mutableStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loginJob by remember { mutableStateOf<Job?>(null) }

    fun startLogin() {
        error = null
        attempt = 0
        running = true
        loginJob = scope.launch {
            runCatching {
                container.ensureCoreReady()
                loginController.login(
                    platform = meta.platform,
                    account = AccountInput(account.trim(), password, url.trim()),
                    progress = { n, _ -> attempt = n },
                )
            }.onSuccess { outcome ->
                running = false
                when (outcome) {
                    is LoginOutcome.Success -> {
                        Toast.makeText(context, "登录成功", Toast.LENGTH_SHORT).show()
                        nav.pop()
                    }
                    is LoginOutcome.Error -> error = outcome.message
                    is LoginOutcome.Manual -> {
                        container.pendingChallenge = outcome.challenge
                        nav.push(Route.ChallengeManual(outcome.taskId))
                    }
                    is LoginOutcome.Cancelled -> { /* stay on page */ }
                }
            }.onFailure {
                running = false
                error = it.message ?: "Core init failed"
            }
        }
    }

    SecondaryScaffold(title = "添加账号", nav = nav) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(top = innerPadding.calculateTopPadding(), bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                OverlayDropdownPreference(
                    title = "平台",
                    items = allPlatformMeta.map { it.displayName },
                    selectedIndex = platformIndex,
                    onSelectedIndexChange = { platformIndex = it },
                )
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(value = account, onValueChange = { account = it }, label = "账号", singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextField(
                        value = password,
                        onValueChange = { password = it },
                        label = meta.passwordLabel,
                        singleLine = !meta.passwordLabel.startsWith("Cookie"),
                        visualTransformation = if (meta.maskPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        keyboardOptions = if (meta.maskPassword) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (meta.requiresUrl) {
                        TextField(value = url, onValueChange = { url = it }, label = meta.urlLabel, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            if (running) {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("正在自动识别验证码…（第 $attempt 次）", color = MiuixTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(text = "取消", onClick = {
                                loginJob?.cancel()
                                running = false
                            }, modifier = Modifier.weight(1f))
                            TextButton(text = "手动输入", onClick = {
                                loginController.requestManual()
                            }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                Button(
                    onClick = { startLogin() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = account.isNotBlank() && password.isNotBlank() && (!meta.requiresUrl || url.isNotBlank()),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("登录并保存", color = MiuixTheme.colorScheme.onPrimary) }
            }
        }
    }

    ErrorDialog(show = error != null, title = "登录失败", message = error ?: "", onDismiss = { error = null })
}
