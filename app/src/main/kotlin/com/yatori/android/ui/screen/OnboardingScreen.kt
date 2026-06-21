package com.yatori.android.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay

private val BG_TOP    = Color(0xFF1A1F3C)
private val BG_BOT    = Color(0xFF0D1117)
private val ACCENT    = Color(0xFF6C8EF5)
private val ACCENT2   = Color(0xFF9B6CF5)
private val SURFACE   = Color(0xFF1E2340)
private val ON_SURF   = Color(0xFFE8ECFF)
private val MUTED     = Color(0xFF8891B8)
private val WARNING   = Color(0xFFFFB74D)

private val DISCLAIMER = """
# 软件完全免费！严禁倒卖！
# 软件完全免费！严禁倒卖！
# 软件完全免费！严禁倒卖！

本开源项目（以下简称"本项目"）所有代码、程序及相关资源，仅面向个人授权用户提供**学习研究、技术交流、个人任务管理**等合法合规的非商业用途，仅供编程学习与技术实践参考。

本项目严格遵守国家法律法规及各大互联网平台规则，**不开发、不提供、不支持**验证码破解、人脸识别绕过、线上考试作弊、账号违规操控、数据爬取窃取等一切违规违法功能。使用者明确知晓，本项目无任何规避平台规则、破坏网络秩序、侵害他人权益的能力与设计初衷。

本项目为开源免费项目，所有公开代码及资源均为公益学习性质。**严禁任何个人或组织以倒卖、售卖、二次封装商用、付费引流、盈利变现**等任何商业形式使用本项目内容，禁止将本项目资源用于各类营利活动，违者需自行承担全部法律责任。

使用者在使用本项目代码、程序及相关资源时，必须严格遵守中华人民共和国相关法律法规、对应平台用户协议及账号授权规则，仅可在自身合法授权的账号、合法权限范围内操作。使用者承诺文明、合规、合法使用本项目，不得利用本项目从事任何违规、侵权、违法犯罪活动。

因使用者**违规使用、越权操作、私自篡改代码、非法商用**或利用本项目从事各类违法违规行为所产生的一切后果，包括但不限于账号封禁、民事赔偿、行政处罚、刑事责任等，均由使用者本人独自承担，与本项目作者及维护团队无任何关联，项目作者不承担任何连带责任。

本项目所有资源均为开源学习分享用途，若本项目内容无意中侵犯任何公司、平台或个人的合法权益，或对相关平台运营造成不良影响，请通过本项目GitHub官方仓库联系作者，我方将在核实后第一时间进行整改、删除、下架等相关处理。

任何个人或组织下载、克隆、使用本项目代码及资源，即代表已完整阅读、充分理解并自愿同意本免责声明全部条款，承诺合规使用本项目。若使用者不认可本声明任意内容，请立即停止使用本项目所有相关资源。

本项目作者保留对本项目及本免责声明的最终解释权及修改权。
""".trimIndent()

@Composable
private fun DisclaimerBody(modifier: Modifier = Modifier) {
    Column(modifier) {
        DISCLAIMER.lines().forEach { line ->
            when {
                line.startsWith("# ") -> Text(
                    line.removePrefix("# "),
                    fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    color = WARNING, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                )
                line.isBlank() -> Spacer(Modifier.height(10.dp))
                else -> {
                    val annotated = buildAnnotatedString {
                        var rest = line
                        while (rest.isNotEmpty()) {
                            val s = rest.indexOf("**")
                            if (s == -1) { append(rest); break }
                            append(rest.substring(0, s)); rest = rest.substring(s + 2)
                            val e = rest.indexOf("**")
                            if (e == -1) { append("**"); append(rest); break }
                            pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = ON_SURF))
                            append(rest.substring(0, e)); pop()
                            rest = rest.substring(e + 2)
                        }
                    }
                    Text(annotated, fontSize = 13.5.sp, color = MUTED,
                        lineHeight = 21.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    vm: OnboardingViewModel = hiltViewModel()
) {
    var secondsLeft by remember { mutableIntStateOf(30) }
    val timerDone = secondsLeft <= 0
    val scrollState = rememberScrollState()
    val scrolledToEnd by remember {
        derivedStateOf { scrollState.value >= scrollState.maxValue && scrollState.maxValue > 0 }
    }
    val canProceed = timerDone && scrolledToEnd

    LaunchedEffect(Unit) { while (secondsLeft > 0) { delay(1000); secondsLeft-- } }

    // 进度动画
    val progress by animateFloatAsState(
        targetValue = if (canProceed) 1f else (30 - secondsLeft) / 30f,
        animationSpec = tween(400), label = "progress"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(BG_TOP, BG_BOT),
                start = Offset.Zero, end = Offset(0f, Float.POSITIVE_INFINITY)))
    ) {
        Column(Modifier.fillMaxSize()) {

            // ── 顶部标题栏 ──────────────────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(BG_TOP)
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("免责声明", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = Color.White, letterSpacing = 2.sp)
            }

            // ── 可滚动内容卡片 ──────────────────────────────────
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SURFACE)
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                ) {
                    DisclaimerBody()
                }
                // 底部渐隐遮罩（未滑到底时提示还有内容）
                if (!scrolledToEnd) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(listOf(Color.Transparent, SURFACE))
                            )
                    )
                }
            }

            // ── 底部操作区 ──────────────────────────────────────
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(BG_BOT)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 条件提示
                val hint = when {
                    !timerDone && !scrolledToEnd ->
                        "请阅读完毕并滑动至底部  ·  ${secondsLeft}s"
                    !timerDone ->
                        "请继续等待  ·  ${secondsLeft}s"
                    !scrolledToEnd ->
                        "↓  请滑动至底部以继续"
                    else -> null
                }

                // 倒计时进度条
                if (!canProceed) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(SURFACE)
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    Brush.horizontalGradient(listOf(ACCENT, ACCENT2))
                                )
                        )
                    }
                }

                if (hint != null) {
                    Text(hint, fontSize = 12.sp, color = MUTED, textAlign = TextAlign.Center)
                }

                Button(
                    onClick = { vm.markOnboarded(); onFinish() },
                    enabled = canProceed,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                        disabledContainerColor = SURFACE,
                        disabledContentColor = MUTED
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (canProceed) Modifier.background(
                                    Brush.horizontalGradient(listOf(ACCENT, ACCENT2)),
                                    RoundedCornerShape(14.dp)
                                ) else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (canProceed) "我已阅读并同意，开始使用" else "开始使用",
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
