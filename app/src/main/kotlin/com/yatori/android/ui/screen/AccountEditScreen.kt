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
import com.yatori.android.ui.viewmodel.AccountViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditScreen(
    accountId: Long?,
    vm: AccountViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val accounts by vm.accounts.collectAsState()
    val existing = remember(accountId, accounts) { accounts.find { it.id == accountId } }

    var platform by remember(existing) { mutableStateOf(existing?.platform ?: Platform.YINGHUA) }
    var url by remember(existing) { mutableStateOf(existing?.url ?: "") }
    var remarkName by remember(existing) { mutableStateOf(existing?.remarkName ?: "") }
    var account by remember(existing) { mutableStateOf(existing?.account ?: "") }
    var password by remember(existing) { mutableStateOf(existing?.password ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }
    var videoMode by remember(existing) { mutableStateOf(existing?.coursesCustom?.videoMode ?: VideoMode.NORMAL) }
    var autoExam by remember(existing) { mutableStateOf(existing?.coursesCustom?.autoExam ?: ExamMode.OFF) }
    var examAutoSubmit by remember(existing) { mutableStateOf(existing?.coursesCustom?.examAutoSubmit ?: 1) }
    var includeCourses by remember(existing) { mutableStateOf(existing?.coursesCustom?.includeCourses?.joinToString(",") ?: "") }
    var excludeCourses by remember(existing) { mutableStateOf(existing?.coursesCustom?.excludeCourses?.joinToString(",") ?: "") }
    var shuffleSw by remember(existing) { mutableStateOf(existing?.coursesCustom?.shuffleSw ?: false) }
    var cxNodeStr by remember(existing) { mutableStateOf((existing?.coursesCustom?.cxNode ?: 3).toString()) }
    var cxChapterTestSw by remember(existing) { mutableStateOf(existing?.coursesCustom?.cxChapterTestSw ?: true) }
    var cxWorkSw by remember(existing) { mutableStateOf(existing?.coursesCustom?.cxWorkSw ?: true) }
    var cxExamSw by remember(existing) { mutableStateOf(existing?.coursesCustom?.cxExamSw ?: true) }
    var examAutoSubmitSw by remember(existing) { mutableStateOf((existing?.coursesCustom?.examAutoSubmit ?: 1) == 1) }

    var platformExpanded by remember { mutableStateOf(false) }
    var videoExpanded by remember { mutableStateOf(false) }
    var examExpanded by remember { mutableStateOf(false) }

    val needsUrl = platform == Platform.YINGHUA || platform == Platform.CANGHUI || platform == Platform.HQKJ

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0),
                title = { Text(if (accountId == null) "添加账号" else "编辑账号") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    TextButton(onClick = {
                        val acc = Account(
                            id = existing?.id ?: 0,
                            platform = platform, url = url.trim(),
                            remarkName = remarkName.trim(), account = account.trim(), password = password,
                            coursesCustom = CoursesCustom(
                                videoMode = videoMode, autoExam = autoExam, cxNode = cxNodeStr.toIntOrNull()?.coerceAtLeast(1) ?: 3,
                                examAutoSubmit = if (examAutoSubmitSw) 1 else 0, shuffleSw = shuffleSw,
                                cxChapterTestSw = cxChapterTestSw, cxWorkSw = cxWorkSw, cxExamSw = cxExamSw,
                                includeCourses = includeCourses.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                                excludeCourses = excludeCourses.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            )
                        )
                        if (existing == null) vm.save(acc) else vm.update(acc)
                        onBack()
                    }, enabled = account.isNotBlank() && password.isNotBlank()) {
                        Text("保存")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.height(4.dp))

            // 平台选择
            ExposedDropdownMenuBox(expanded = platformExpanded, onExpandedChange = { platformExpanded = it }) {
                OutlinedTextField(
                    value = platform.displayName, onValueChange = {},
                    readOnly = true, label = { Text("平台") }, modifier = Modifier.fillMaxWidth().menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(platformExpanded) }
                )
                ExposedDropdownMenu(expanded = platformExpanded, onDismissRequest = { platformExpanded = false }) {
                    Platform.entries.forEach { p ->
                        DropdownMenuItem(text = { Text(p.displayName) }, onClick = { platform = p; platformExpanded = false })
                    }
                }
            }

            if (needsUrl)
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("平台URL") },
                    placeholder = { Text("https://xxx.xxx.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))

            OutlinedTextField(remarkName, { remarkName = it }, Modifier.fillMaxWidth(), label = { Text("备注名（可选）") })
            OutlinedTextField(account, { account = it }, Modifier.fillMaxWidth(), label = { Text("账号 *") })
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("密码 *") },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                }})

            HorizontalDivider()
            Text("课程设置", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

            // 视频模式
            ExposedDropdownMenuBox(expanded = videoExpanded, onExpandedChange = { videoExpanded = it }) {
                OutlinedTextField(videoMode.label, {}, readOnly = true, label = { Text("刷课模式") },
                    modifier = Modifier.fillMaxWidth().menuAnchor(), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(videoExpanded) })
                ExposedDropdownMenu(expanded = videoExpanded, onDismissRequest = { videoExpanded = false }) {
                    VideoMode.entries.forEach { m ->
                        DropdownMenuItem(text = { Text(m.label) }, onClick = { videoMode = m; videoExpanded = false })
                    }
                }
            }

            // 考试模式
            ExposedDropdownMenuBox(expanded = examExpanded, onExpandedChange = { examExpanded = it }) {
                OutlinedTextField(autoExam.label, {}, readOnly = true, label = { Text("自动考试") },
                    modifier = Modifier.fillMaxWidth().menuAnchor(), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(examExpanded) })
                ExposedDropdownMenu(expanded = examExpanded, onDismissRequest = { examExpanded = false }) {
                    ExamMode.entries.forEach { m ->
                        DropdownMenuItem(text = { Text(m.label) }, onClick = { autoExam = m; examExpanded = false })
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("打乱学习顺序", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = shuffleSw, onCheckedChange = { shuffleSw = it })
            }

            // 自动考试相关选项（仅在开启考试时显示）
            if (autoExam != ExamMode.OFF) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("自动交卷", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = examAutoSubmitSw, onCheckedChange = { examAutoSubmitSw = it })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("开启章节测验", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = cxChapterTestSw, onCheckedChange = { cxChapterTestSw = it })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("开启课程作业", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = cxWorkSw, onCheckedChange = { cxWorkSw = it })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("开启课程考试", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = cxExamSw, onCheckedChange = { cxExamSw = it })
                }
            }

            if (platform == Platform.XUEXITONG) {
                OutlinedTextField(
                    cxNodeStr, { cxNodeStr = it.filter { c -> c.isDigit() } },
                    Modifier.fillMaxWidth(), label = { Text("多任务点并行数（学习通）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true
                )
            }
            OutlinedTextField(includeCourses, { includeCourses = it }, Modifier.fillMaxWidth(),
                label = { Text("仅学习课程（英文逗号分隔，可空）") })
            OutlinedTextField(excludeCourses, { excludeCourses = it }, Modifier.fillMaxWidth(),
                label = { Text("排除课程（英文逗号分隔，可空）") })

            Spacer(Modifier.height(32.dp))
        }
    }
}
