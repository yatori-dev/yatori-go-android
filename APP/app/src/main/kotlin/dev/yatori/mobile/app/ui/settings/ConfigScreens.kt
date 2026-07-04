package dev.yatori.mobile.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.yatori.mobile.app.di.AppContainer
import dev.yatori.mobile.app.ui.common.SectionLabel
import dev.yatori.mobile.app.ui.common.SecondaryScaffold
import dev.yatori.mobile.app.ui.nav.Navigator
import dev.yatori.mobile.api.dto.AiSetting
import dev.yatori.mobile.api.dto.ApiQueSetting
import dev.yatori.mobile.api.dto.EmailInform
import dev.yatori.mobile.api.dto.MobileConfig
import dev.yatori.mobile.api.dto.SessionData
import dev.yatori.mobile.runtime.operation.AiProviderCatalog
import dev.yatori.mobile.runtime.operation.AnswerRequest
import dev.yatori.mobile.runtime.operation.ExternalQuestionBankAnswerProvider
import dev.yatori.mobile.runtime.operation.ExternalQuestionBankCatalog
import dev.yatori.mobile.runtime.operation.HostAiAnswerProvider
import dev.yatori.mobile.app.service.EmailSender
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Email notification settings — fields from EmailInform (sw/smtpHost/smtpPort/email/password). */
@Composable
fun EmailNotificationScreen(container: AppContainer, nav: Navigator) {
    val repo = container.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf(repo.loadSavedConfig() ?: MobileConfig()) }
    var email by remember { mutableStateOf(cfg.setting.emailInform) }
    var testing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { repo.fetchConfig() }.onSuccess { cfg = it; email = it.setting.emailInform }
    }

    fun persist(updated: EmailInform) {
        email = updated
        scope.launch {
            val newCfg = cfg.copy(setting = cfg.setting.copy(emailInform = updated))
            runCatching { repo.applyConfig(newCfg) }.onSuccess { cfg = newCfg }
        }
    }

    fun testEmail() {
        val missing = when {
            email.smtpHost.isBlank() -> "请先填写 SMTP 服务器"
            email.smtpPort.isBlank() -> "请先填写 SMTP 端口"
            email.email.isBlank()    -> "请先填写发件邮箱"
            else -> null
        }
        if (missing != null) { Toast.makeText(context, missing, Toast.LENGTH_SHORT).show(); return }
        testing = true
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                EmailSender.send(
                    email.copy(sw = 1),
                    listOf(email.email),
                    "这是一封来自 Yatori 的测试邮件，收到说明邮件通知配置正确。",
                )
            }
            testing = false
            Toast.makeText(context, if (err == null) "测试邮件发送成功" else "发送失败：$err", Toast.LENGTH_LONG).show()
        }
    }

    SecondaryScaffold(title = "邮件通知", nav = nav) { innerPadding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp).padding(top = innerPadding.calculateTopPadding(), bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                SwitchPreference(
                    title = "启用邮件通知",
                    checked = email.sw == 1,
                    onCheckedChange = { persist(email.copy(sw = if (it) 1 else 0)) },
                )
            }
            if (email.sw == 1) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextField(value = email.smtpHost, onValueChange = { persist(email.copy(smtpHost = it)) }, label = "SMTP 服务器", singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextField(value = email.smtpPort, onValueChange = { persist(email.copy(smtpPort = it)) }, label = "SMTP 端口（如 465/587）", singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        TextField(value = email.email, onValueChange = { persist(email.copy(email = it)) }, label = "发件邮箱", singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextField(value = email.password, onValueChange = { persist(email.copy(password = it)) }, label = "邮箱密码 / 授权码", singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    }
                }
                Button(
                    onClick = { testEmail() },
                    enabled = !testing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(if (testing) "发送中…" else "发送测试邮件", color = MiuixTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

private val AI_TYPES = AiProviderCatalog.providers.map { it.type }
private val AI_TYPE_LABELS = AiProviderCatalog.providers.map { it.label }
private val QUESTION_BANK_TYPES = ExternalQuestionBankCatalog.providers.map { it.type }
private val QUESTION_BANK_TYPE_LABELS = ExternalQuestionBankCatalog.providers.map { it.label }

/** Answer settings — third-party question bank and AI fallback fields. */
@Composable
fun AiSettingsScreen(container: AppContainer, nav: Navigator) {
    val repo = container.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf(repo.loadSavedConfig() ?: MobileConfig()) }
    var ai by remember { mutableStateOf(cfg.setting.aiSetting) }
    var apiQue by remember { mutableStateOf(cfg.setting.apiQueSetting) }
    var testingAi by remember { mutableStateOf(false) }
    var testingQuestionBank by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { repo.fetchConfig() }.onSuccess {
            cfg = it
            ai = it.setting.aiSetting
            apiQue = it.setting.apiQueSetting
        }
    }

    fun persist(updated: AiSetting) {
        ai = updated
        scope.launch {
            val newCfg = cfg.copy(setting = cfg.setting.copy(aiSetting = updated))
            runCatching { repo.applyConfig(newCfg) }.onSuccess { cfg = newCfg }
        }
    }

    fun persistApiQue(updated: ApiQueSetting) {
        apiQue = updated
        scope.launch {
            val newCfg = cfg.copy(setting = cfg.setting.copy(apiQueSetting = updated))
            runCatching { repo.applyConfig(newCfg) }.onSuccess { cfg = newCfg }
        }
    }

    val typeIndex = AI_TYPES.indexOf(ai.aiType).let { if (it < 0) AI_TYPES.lastIndex else it }
    val aiSpec = AiProviderCatalog.spec(ai.aiType)
    val questionBankType = apiQue.exType.orEmpty().ifBlank {
        if (apiQue.url.orEmpty().isBlank()) ExternalQuestionBankCatalog.CUSTOM else ExternalQuestionBankCatalog.CUSTOM
    }
    val questionBankIndex = QUESTION_BANK_TYPES.indexOf(questionBankType)
        .let { if (it < 0) QUESTION_BANK_TYPES.indexOf(ExternalQuestionBankCatalog.CUSTOM) else it }
    val questionBankSpec = ExternalQuestionBankCatalog.spec(questionBankType)
    val questionBankUsesCustomUrl = questionBankSpec.type == ExternalQuestionBankCatalog.CUSTOM
    val modelLabel = if (aiSpec.hasDefaultModel) {
        "模型名称（不填默认 ${aiSpec.defaultModel}）"
    } else {
        "模型名称（必填）"
    }

    fun testAi() {
        val missing = when {
            ai.apiKey.isBlank() -> "请先填写 API Key"
            aiSpec.requiresCustomUrl && ai.aiUrl.isBlank() -> "请先填写 API 地址"
            aiSpec.requiresModel && ai.model.isBlank() -> "请先填写模型名称"
            else -> null
        }
        if (missing != null) {
            Toast.makeText(context, missing, Toast.LENGTH_SHORT).show()
            return
        }
        testingAi = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val question = com.google.gson.JsonObject().apply {
                    addProperty("type", "简答题")
                    addProperty("content", "请原样回答：测试成功")
                }
                val provider = HostAiAnswerProvider(settingProvider = { ai })
                provider.answers(
                    AnswerRequest(
                        session = SessionData("system", "ai-check"),
                        ctx = com.google.gson.JsonObject(),
                        question = question,
                        prompt = "请原样回答：测试成功",
                        dryRun = false,
                        label = "ai-check",
                    ),
                ).isNotEmpty()
            }
            testingAi = false
            Toast.makeText(context, if (ok) "AI 配置可用" else "AI 配置不可用", Toast.LENGTH_SHORT).show()
        }
    }

    fun testQuestionBank() {
        val missing = when {
            questionBankUsesCustomUrl && apiQue.url.orEmpty().isBlank() -> "请先填写题库地址"
            !questionBankUsesCustomUrl && apiQue.exToken.orEmpty().isBlank() -> "请先填写题库 Token"
            else -> null
        }
        if (missing != null) {
            Toast.makeText(context, missing, Toast.LENGTH_SHORT).show()
            return
        }
        testingQuestionBank = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val question = com.google.gson.JsonObject().apply {
                    addProperty("type", "单选题")
                    addProperty("content", "测试题")
                    add(
                        "options",
                        com.google.gson.JsonObject().apply {
                            addProperty("A", "测试选项A")
                            addProperty("B", "测试选项B")
                        },
                    )
                }
                val provider = ExternalQuestionBankAnswerProvider(settingProvider = { apiQue })
                provider.answers(
                    AnswerRequest(
                        session = SessionData("system", "question-bank-check"),
                        ctx = com.google.gson.JsonObject(),
                        question = question,
                        prompt = "测试题",
                        dryRun = false,
                        label = "question-bank-check",
                    ),
                ).isNotEmpty()
            }
            testingQuestionBank = false
            Toast.makeText(context, if (ok) "题库配置可用" else "题库没有返回答案", Toast.LENGTH_SHORT).show()
        }
    }

    SecondaryScaffold(title = "答题设置", nav = nav) { innerPadding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp).padding(top = innerPadding.calculateTopPadding(), bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel("AI答题配置")
            Card {
                OverlayDropdownPreference(
                    title = "AI 类型",
                    items = AI_TYPE_LABELS,
                    selectedIndex = typeIndex,
                    onSelectedIndexChange = { persist(ai.copy(aiType = AI_TYPES[it])) },
                )
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (aiSpec.requiresCustomUrl) {
                        TextField(value = ai.aiUrl, onValueChange = { persist(ai.copy(aiUrl = it)) }, label = "API 地址", singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    TextField(value = ai.model, onValueChange = { persist(ai.copy(model = it)) }, label = modelLabel, singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextField(value = ai.apiKey, onValueChange = { persist(ai.copy(apiKey = it)) }, label = "API Key", singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
            }
            Button(
                onClick = { testAi() },
                enabled = !testingAi,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(if (testingAi) "正在测试 AI" else "测试 AI", color = MiuixTheme.colorScheme.onPrimary)
            }
            SectionLabel("第三方题库配置")
            Card {
                OverlayDropdownPreference(
                    title = "题库类型",
                    items = QUESTION_BANK_TYPE_LABELS,
                    selectedIndex = questionBankIndex,
                    onSelectedIndexChange = { persistApiQue(apiQue.copy(exType = QUESTION_BANK_TYPES[it])) },
                )
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (questionBankUsesCustomUrl) {
                        // Custom: needs both URL and an optional Bearer token for auth
                        TextField(value = apiQue.url.orEmpty(), onValueChange = { persistApiQue(apiQue.copy(url = it)) }, label = "题库地址", singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextField(value = apiQue.exToken.orEmpty(), onValueChange = { persistApiQue(apiQue.copy(exToken = it)) }, label = "题库 Token（如需鉴权则填写）", singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    } else {
                        TextField(value = apiQue.exToken.orEmpty(), onValueChange = { persistApiQue(apiQue.copy(exToken = it)) }, label = "题库 Token", singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Button(
                onClick = { testQuestionBank() },
                enabled = !testingQuestionBank,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(if (testingQuestionBank) "正在测试题库" else "测试题库", color = MiuixTheme.colorScheme.onPrimary)
            }
        }
    }
}
