package com.yatori.android.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yatori.android.domain.model.*
import com.yatori.android.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsState()
    var draft by remember(s) { mutableStateOf(s) }
    var aiTypeExpanded by remember { mutableStateOf(false) }
    var logLevelExpanded by remember { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionTitle("外观")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("跟随系统")
            Switch(checked = draft.followSystem, onCheckedChange = { draft = draft.copy(followSystem = it); vm.save(draft) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("深色模式", color = if (draft.followSystem) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurface)
            Switch(
                checked = draft.darkMode,
                onCheckedChange = { draft = draft.copy(darkMode = it); vm.save(draft) },
                enabled = !draft.followSystem
            )
        }
        SectionTitle("日志")
        val logLevels = listOf("ALL", "DEBUG", "INFO", "WARN", "ERROR")
        val logLevelLabels = mapOf(
            "ALL" to "全部", "DEBUG" to "DEBUG", "INFO" to "INFO", "WARN" to "WARN", "ERROR" to "ERROR"
        )
        ExposedDropdownMenuBox(expanded = logLevelExpanded, onExpandedChange = { logLevelExpanded = it }) {
            OutlinedTextField(
                logLevelLabels[draft.logLevel] ?: draft.logLevel, {}, readOnly = true,
                label = { Text("日志显示级别") }, modifier = Modifier.fillMaxWidth().menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(logLevelExpanded) }
            )
            ExposedDropdownMenu(expanded = logLevelExpanded, onDismissRequest = { logLevelExpanded = false }) {
                logLevels.forEach { lv ->
                    DropdownMenuItem(
                        text = { Text(logLevelLabels[lv] ?: lv) },
                        onClick = { draft = draft.copy(logLevel = lv); vm.save(draft); logLevelExpanded = false }
                    )
                }
            }
        }

        SectionTitle("邮箱通知")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("开启邮箱通知")
            Switch(checked = draft.emailSw, onCheckedChange = { draft = draft.copy(emailSw = it); vm.save(draft) })
        }
        if (draft.emailSw) {
            OutlinedTextField(draft.smtpHost, { draft = draft.copy(smtpHost = it) }, Modifier.fillMaxWidth(), label = { Text("SMTP 服务器") }, singleLine = true)
            OutlinedTextField(draft.smtpPort.toString(), { draft = draft.copy(smtpPort = it.toIntOrNull() ?: 465) }, Modifier.fillMaxWidth(),
                label = { Text("SMTP 端口") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            OutlinedTextField(draft.smtpUser, { draft = draft.copy(smtpUser = it) }, Modifier.fillMaxWidth(), label = { Text("发件邮箱") }, singleLine = true)
            OutlinedTextField(draft.smtpPassword, { draft = draft.copy(smtpPassword = it) }, Modifier.fillMaxWidth(), label = { Text("邮箱密码/授权码") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true)
        }

        SectionTitle("AI 答题设置")
        ExposedDropdownMenuBox(expanded = aiTypeExpanded, onExpandedChange = { aiTypeExpanded = it }) {
            OutlinedTextField(
                draft.aiType.displayName, {}, readOnly = true,
                label = { Text("AI 提供商") }, modifier = Modifier.fillMaxWidth().menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(aiTypeExpanded) }
            )
            ExposedDropdownMenu(expanded = aiTypeExpanded, onDismissRequest = { aiTypeExpanded = false }) {
                AiProvider.entries.forEach { p ->
                    DropdownMenuItem(text = { Text(p.displayName) }, onClick = { draft = draft.copy(aiType = p); aiTypeExpanded = false })
                }
            }
        }
        OutlinedTextField(draft.aiModel, { draft = draft.copy(aiModel = it) }, Modifier.fillMaxWidth(), label = { Text("AI 模型（选填）") }, singleLine = true)
        OutlinedTextField(draft.aiApiKey, { draft = draft.copy(aiApiKey = it) }, Modifier.fillMaxWidth(), label = { Text("API Key") },
            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                Icon(if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
            }}, singleLine = true)
        if (draft.aiType == AiProvider.OTHER)
            OutlinedTextField(draft.aiUrl, { draft = draft.copy(aiUrl = it) }, Modifier.fillMaxWidth(), label = { Text("AI 接口地址") }, singleLine = true)

        Button(onClick = { vm.save(draft) }, Modifier.fillMaxWidth()) { Text("保存设置") }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
    HorizontalDivider()
}
