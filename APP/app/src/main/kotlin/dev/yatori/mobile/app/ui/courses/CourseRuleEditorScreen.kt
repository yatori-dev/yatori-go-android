package dev.yatori.mobile.app.ui.courses

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.yatori.mobile.api.dto.CourseItem
import dev.yatori.mobile.api.dto.MobileConfig
import dev.yatori.mobile.app.di.AppContainer
import dev.yatori.mobile.app.platform.IntChoice
import dev.yatori.mobile.app.platform.platformCourseConfig
import dev.yatori.mobile.app.service.autoReloginWithOcr
import dev.yatori.mobile.app.service.loadCoursesWithSessionRecovery
import dev.yatori.mobile.app.ui.common.SecondaryScaffold
import dev.yatori.mobile.app.ui.common.TopBarAction
import dev.yatori.mobile.app.ui.nav.Navigator
import dev.yatori.mobile.runtime.operation.CourseSelectionRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Dropdown option values for course filtering mode. */
private const val COURSE_MODE_ALL = 0
private const val COURSE_MODE_INCLUDE = 1
private const val COURSE_MODE_EXCLUDE = 2

@Composable
fun CourseRuleEditorScreen(container: AppContainer, nav: Navigator, platform: String, account: String) {
    val context = LocalContext.current
    val repo = container.repository
    val scope = rememberCoroutineScope()
    val matrix = remember(platform) { platformCourseConfig(platform) }
    var cfg by remember { mutableStateOf(repo.loadSavedConfig() ?: MobileConfig()) }
    var remarkName by remember { mutableStateOf("") }
    var informEmails by remember { mutableStateOf("") }

    // Course filter mode: 0=全部课程, 1=仅学习课程, 2=排除课程
    var courseMode by remember { mutableStateOf(COURSE_MODE_ALL) }
    // Selected course names used for include or exclude list
    var selectedCourses by remember { mutableStateOf(setOf<String>()) }
    // Courses loaded from local cache (populated by getCourses or loadCourseCache)
    var cachedCourses by remember { mutableStateOf<List<CourseItem>>(emptyList()) }
    var syncing by remember { mutableStateOf(false) }
    var courseListExpanded by remember { mutableStateOf(false) }

    var videoModel by remember { mutableStateOf(matrix.videoModes.firstOrNull()?.value ?: 0) }
    var autoExam by remember { mutableStateOf(matrix.autoExamModes.firstOrNull()?.value ?: 0) }
    var examAutoSubmit by remember { mutableStateOf(matrix.submitModes.firstOrNull()?.value ?: 0) }
    var studyTime by remember { mutableStateOf("") }
    var cxNode by remember { mutableStateOf("3") }
    var cxChapterTest by remember { mutableStateOf(true) }
    var cxWork by remember { mutableStateOf(true) }
    var cxExam by remember { mutableStateOf(true) }
    var shuffle by remember { mutableStateOf(false) }

    fun loadFrom(user: JsonObject?) {
        if (user == null) return
        remarkName = user.str("remarkName")
        informEmails = user.arrayText("informEmails")
        val cc = user.obj("coursesCustom")
        val includeList = cc.arrayText("includeCourses")
            .split(",", "，").map { it.trim() }.filter { it.isNotBlank() }
        val excludeList = cc.arrayText("excludeCourses")
            .split(",", "，").map { it.trim() }.filter { it.isNotBlank() }
        when {
            includeList.isNotEmpty() -> { courseMode = COURSE_MODE_INCLUDE; selectedCourses = includeList.toSet() }
            excludeList.isNotEmpty() -> { courseMode = COURSE_MODE_EXCLUDE; selectedCourses = excludeList.toSet() }
            else                     -> { courseMode = COURSE_MODE_ALL;    selectedCourses = emptySet() }
        }
        videoModel = cc.int("videoModel").takeIf { v -> matrix.videoModes.any { it.value == v } }
            ?: (matrix.videoModes.firstOrNull()?.value ?: 0)
        autoExam = cc.int("autoExam").takeIf { v -> matrix.autoExamModes.any { it.value == v } }
            ?: (matrix.autoExamModes.firstOrNull()?.value ?: 0)
        examAutoSubmit = cc.int("examAutoSubmit").takeIf { v -> matrix.submitModes.any { it.value == v } }
            ?: (matrix.submitModes.firstOrNull()?.value ?: 0)
        studyTime = cc.str("studyTime")
        cxNode = cc.positiveInt("cxNode", 3).toString()
        cxChapterTest = cc.int("cxChapterTestSw", 1) == 1
        cxWork = cc.int("cxWorkSw", 1) == 1
        cxExam = cc.int("cxExamSw", 1) == 1
        shuffle = cc.int("shuffleSw") == 1
    }

    LaunchedEffect(platform, account) {
        // Load cached courses immediately (no network needed)
        cachedCourses = withContext(Dispatchers.IO) { repo.loadCachedCourses(platform, account) }
        runCatching { repo.fetchConfig() }
            .onSuccess { loaded -> cfg = loaded; loadFrom(loaded.findUser(platform, account)) }
            .onFailure { loadFrom(cfg.findUser(platform, account)) }
    }

    fun syncCourses() {
        val accountUrl = cfg.findUser(platform, account)?.str("url").orEmpty()
        val session = repo.getSession(platform, account, accountUrl)
            ?: repo.listSessions().firstOrNull { it.platform == platform && it.account == account }?.session
            ?: run {
                Toast.makeText(context, "请先登录账号", Toast.LENGTH_SHORT).show()
                return
            }
        syncing = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    loadCoursesWithSessionRecovery(
                        initialSession = session,
                        fetchCourses = repo::getCourses,
                        relogin = { autoReloginWithOcr(container, platform, account, accountUrl) },
                    )
                }
            }.onSuccess { courses ->
                cachedCourses = courses
                Toast.makeText(context, "已同步 ${courses.size} 门课程", Toast.LENGTH_SHORT).show()
            }.onFailure { Toast.makeText(context, "同步失败：${it.message}", Toast.LENGTH_SHORT).show() }
            syncing = false
        }
    }

    fun persist() {
        val showNodeCount = matrix.supportsXuexitongNodeConcurrency && videoModel == 3
        val answerEnabled = matrix.autoExamModes.isNotEmpty() && autoExam != 0
        val nodeCount = if (showNodeCount) {
            positiveCxNodeOrNull(cxNode) ?: run {
                Toast.makeText(context, "多任务点数量要大于 0", Toast.LENGTH_SHORT).show()
                return
            }
        } else null
        val updatedUsers = cfg.users.map { it.deepCopy() }.toMutableList()
        val index = updatedUsers.indexOfFirst { it.matchesUser(platform, account) }
        val user = if (index >= 0) updatedUsers[index] else JsonObject().also { updatedUsers.add(it) }
        val credential = repo.getCredential(platform, account)
        user.addProperty("accountType", platform)
        user.addProperty("account", account)
        if (credential != null) {
            user.addProperty("password", credential.password)
            user.addProperty("url", credential.url)
        }
        user.addProperty("remarkName", remarkName)
        user.add("informEmails", informEmails.toJsonArray())
        user.remove("isProxy")

        val cc = user.obj("coursesCustom").deepCopy()
        val courseNames = selectedCourses.filter { it.isNotBlank() }
        cc.add("includeCourses", if (courseMode == COURSE_MODE_INCLUDE) courseNames.toJsonArray() else JsonArray())
        cc.add("excludeCourses", if (courseMode == COURSE_MODE_EXCLUDE) courseNames.toJsonArray() else JsonArray())
        if (matrix.videoModes.isNotEmpty()) cc.addProperty("videoModel", videoModel) else cc.remove("videoModel")
        if (matrix.autoExamModes.isNotEmpty()) cc.addProperty("autoExam", autoExam) else cc.remove("autoExam")
        if (matrix.submitModes.isNotEmpty() && (matrix.autoExamModes.isEmpty() || answerEnabled)) {
            cc.addProperty("examAutoSubmit", examAutoSubmit)
        } else {
            cc.remove("examAutoSubmit")
        }
        if (matrix.supportsStudyTimeRange) cc.addProperty("studyTime", studyTime) else cc.remove("studyTime")
        if (showNodeCount && nodeCount != null) cc.addProperty("cxNode", nodeCount) else cc.remove("cxNode")
        if (matrix.supportsXuexitongChapterTest && answerEnabled) cc.addProperty("cxChapterTestSw", if (cxChapterTest) 1 else 0) else cc.remove("cxChapterTestSw")
        if (matrix.supportsXuexitongWork && answerEnabled) cc.addProperty("cxWorkSw", if (cxWork) 1 else 0) else cc.remove("cxWorkSw")
        if (matrix.supportsXuexitongExam && answerEnabled) cc.addProperty("cxExamSw", if (cxExam) 1 else 0) else cc.remove("cxExamSw")
        if (matrix.supportsXuexitongShuffle) cc.addProperty("shuffleSw", if (shuffle) 1 else 0) else cc.remove("shuffleSw")
        cc.remove("coursesSettings")
        user.add("coursesCustom", cc)

        val newCfg = cfg.copy(users = updatedUsers)
        scope.launch {
            runCatching { repo.applyConfig(newCfg) }
                .onSuccess { cfg = newCfg; Toast.makeText(context, "配置已保存", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, it.message ?: "保存失败", Toast.LENGTH_SHORT).show() }
        }
    }

    val courseModeChoices = remember {
        listOf(
            IntChoice(COURSE_MODE_ALL, "全部课程"),
            IntChoice(COURSE_MODE_INCLUDE, "仅学习课程"),
            IntChoice(COURSE_MODE_EXCLUDE, "排除课程"),
        )
    }

    SecondaryScaffold(
        title = "课程配置",
        nav = nav,
        actions = {
            TopBarAction(
                icon = Icons.Rounded.Refresh,
                contentDescription = "同步课程",
                onClick = { if (!syncing) syncCourses() },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(top = innerPadding.calculateTopPadding(), bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Basic account info
            Card(insideMargin = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextField(value = remarkName, onValueChange = { remarkName = it }, label = "备注", singleLine = true, modifier = Modifier.fillMaxWidth())
                    TextField(value = informEmails, onValueChange = { informEmails = it }, label = "通知邮箱，逗号分隔", singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }

            // Course mode (全部/仅学习/排除)
            ModeDropdown("课程模式", courseModeChoices, courseMode) { newMode ->
                courseMode = newMode
                if (newMode == COURSE_MODE_ALL) selectedCourses = emptySet()
            }

            // Course multi-select — only shown when a filter mode is active
            if (courseMode != COURSE_MODE_ALL) {
                CourseSelectionCard(
                    cachedCourses = cachedCourses,
                    selectedCourses = selectedCourses,
                    expanded = courseListExpanded,
                    onExpandToggle = { courseListExpanded = !courseListExpanded },
                    onToggleCourse = { name ->
                        selectedCourses = if (name in selectedCourses) selectedCourses - name else selectedCourses + name
                    },
                )
            }

            // Platform-specific options
            val showNodeCount = matrix.supportsXuexitongNodeConcurrency && videoModel == 3
            val answerEnabled = matrix.autoExamModes.isNotEmpty() && autoExam != 0
            val showAnswerSubmit = matrix.submitModes.isNotEmpty() && (matrix.autoExamModes.isEmpty() || answerEnabled)
            val showXuexitongAnswerSwitches =
                answerEnabled && (matrix.supportsXuexitongChapterTest || matrix.supportsXuexitongWork || matrix.supportsXuexitongExam)

            ModeDropdown("视频模式", matrix.videoModes, videoModel) { videoModel = it }
            if (matrix.autoExamModes.isNotEmpty()) ModeDropdown("答题模式", matrix.autoExamModes, autoExam) { autoExam = it }
            if (showAnswerSubmit) ModeDropdown("答题提交", matrix.submitModes, examAutoSubmit) { examAutoSubmit = it }

            if (matrix.supportsStudyTimeRange) {
                Card(insideMargin = PaddingValues(16.dp)) {
                    TextField(value = studyTime, onValueChange = { studyTime = it }, label = "WeLearn 学时范围，如 20-30", singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }

            if (showNodeCount || showXuexitongAnswerSwitches || matrix.supportsXuexitongShuffle) {
                Card(insideMargin = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (showNodeCount) {
                            TextField(
                                value = cxNode,
                                onValueChange = { cxNode = it.filter(Char::isDigit) },
                                label = "同时跑几个任务点",
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (showXuexitongAnswerSwitches) {
                            if (matrix.supportsXuexitongChapterTest) SwitchPreference(title = "学习通章测", checked = cxChapterTest, onCheckedChange = { cxChapterTest = it })
                            if (matrix.supportsXuexitongWork) SwitchPreference(title = "学习通作业", checked = cxWork, onCheckedChange = { cxWork = it })
                            if (matrix.supportsXuexitongExam) SwitchPreference(title = "学习通考试", checked = cxExam, onCheckedChange = { cxExam = it })
                        }
                        CompactSwitchRow(title = "打乱学习顺序", checked = shuffle, onCheckedChange = { shuffle = it })
                    }
                }
            }

            Button(
                onClick = { persist() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text("保存配置", color = MiuixTheme.colorScheme.onPrimary)
            }
        }
    }
}

/**
 * Expandable multi-select card for picking course names from the cached list.
 *
 * - No cached courses → shows hint text only (no expand button)
 * - Courses available, none selected → "点击选择课程" + expand button
 * - Courses selected → "已选 N 个课程" + expand button
 * - Expanded → checkbox list of all cached course names
 */
@Composable
private fun CourseSelectionCard(
    cachedCourses: List<CourseItem>,
    selectedCourses: Set<String>,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    onToggleCourse: (String) -> Unit,
) {
    Card(insideMargin = PaddingValues(0.dp)) {
        Column {
            // Header row: hint / selection summary + expand toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (cachedCourses.isNotEmpty()) Modifier.clickable { onExpandToggle() } else Modifier)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (cachedCourses.isEmpty()) {
                        Text(
                            "没有课程信息",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            "请点击右上角刷新",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    } else if (selectedCourses.isEmpty()) {
                        Text(
                            "点击选择课程",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    } else {
                        Text(
                            "已选 ${selectedCourses.size} 个课程",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (cachedCourses.isNotEmpty()) {
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            // Expandable course checklist
            AnimatedVisibility(visible = expanded && cachedCourses.isNotEmpty()) {
                Column {
                    // Thin divider
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    cachedCourses.forEach { course ->
                        val checked = course.name in selectedCourses
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleCourse(course.name) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = course.name,
                                modifier = Modifier.weight(1f).padding(end = 12.dp),
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Icon(
                                imageVector = if (checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                                contentDescription = if (checked) "已选" else "未选",
                                tint = if (checked) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun CompactSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = MiuixTheme.colorScheme.onSurface)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ModeDropdown(title: String, choices: List<IntChoice>, value: Int, onChange: (Int) -> Unit) {
    if (choices.isEmpty()) return
    val selected = choices.indexOfFirst { it.value == value }.let { if (it < 0) 0 else it }
    Card {
        OverlayDropdownPreference(
            title = title,
            items = choices.map { it.label },
            selectedIndex = selected,
            onSelectedIndexChange = { onChange(choices[it].value) },
        )
    }
}

internal fun buildRule(modeIndex: Int, keywords: String): CourseSelectionRule =
    if (modeIndex == 1) {
        CourseSelectionRule(
            mode = CourseSelectionRule.Mode.MANUAL_CONTAINS,
            includeKeywords = keywords.split(",", "，").map { it.trim() }.filter { it.isNotBlank() },
        )
    } else {
        CourseSelectionRule(mode = CourseSelectionRule.Mode.ALL)
    }

private fun MobileConfig.findUser(platform: String, account: String): JsonObject? =
    users.firstOrNull { it.matchesUser(platform, account) }

private fun JsonObject.matchesUser(platform: String, account: String): Boolean =
    str("account").equals(account, ignoreCase = false) &&
        str("accountType").equals(platform, ignoreCase = true)

private fun JsonObject.obj(key: String): JsonObject =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

private fun JsonObject.str(key: String): String =
    runCatching { get(key)?.asString.orEmpty() }.getOrDefault("")

private fun JsonObject.int(key: String, default: Int = 0): Int =
    runCatching { get(key)?.asInt ?: default }.getOrDefault(default)

private fun JsonObject.positiveInt(key: String, default: Int): Int =
    int(key, default).takeIf { it > 0 } ?: default

internal fun positiveCxNodeOrNull(raw: String): Int? =
    raw.trim().toIntOrNull()?.takeIf { it > 0 }

private fun JsonObject.arrayText(key: String): String =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { runCatching { it.asString }.getOrNull() }
        ?.joinToString(", ")
        .orEmpty()

private fun String.toJsonArray(): JsonArray = JsonArray().also { arr ->
    split(",", "，").map { it.trim() }.filter { it.isNotBlank() }.forEach { arr.add(it) }
}

private fun Collection<String>.toJsonArray(): JsonArray = JsonArray().also { arr ->
    forEach { arr.add(it) }
}
