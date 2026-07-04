package dev.yatori.mobile.app.platform

import dev.yatori.mobile.api.Platform

data class IntChoice(val value: Int, val label: String)

data class PlatformCourseConfig(
    val platformId: String,
    val videoModes: List<IntChoice> = emptyList(),
    val autoExamModes: List<IntChoice> = emptyList(),
    val submitModes: List<IntChoice> = emptyList(),
    val supportsStudyTimeRange: Boolean = false,
    val supportsXuexitongNodeConcurrency: Boolean = false,
    val supportsXuexitongChapterTest: Boolean = false,
    val supportsXuexitongWork: Boolean = false,
    val supportsXuexitongExam: Boolean = false,
    val supportsXuexitongShuffle: Boolean = false,
)

private val offNormal = listOf(
    IntChoice(0, "不学习"),
    IntChoice(1, "普通模式"),
)

private val offNormalFast = listOf(
    IntChoice(0, "不学习"),
    IntChoice(1, "普通模式"),
    IntChoice(2, "快速模式"),
)

private val yinghuaVideo = listOf(
    IntChoice(0, "不学习"),
    IntChoice(1, "普通模式"),
    IntChoice(2, "暴力模式"),
    IntChoice(3, "去红模式"),
)

private val xuexitongVideo = listOf(
    IntChoice(0, "不看视频"),
    IntChoice(1, "普通模式"),
    IntChoice(2, "多课程模式"),
    IntChoice(3, "多任务点模式"),
)

private val welearnVideo = listOf(
    IntChoice(0, "不学习"),
    IntChoice(1, "学时模式"),
    IntChoice(2, "完成度模式"),
)

private val hostAiOnly = listOf(
    IntChoice(0, "不答题"),
    IntChoice(1, "AI 答题"),
)

private val aiOrQuestionBank = listOf(
    IntChoice(0, "不答题"),
    IntChoice(1, "AI 答题"),
    IntChoice(2, "题库答题"),
)

private val xuexitongAnswer = listOf(
    IntChoice(0, "不答题"),
    IntChoice(1, "AI 答题"),
    IntChoice(2, "题库答题"),
    IntChoice(3, "学习通 AI"),
)

private val platformSolutionAnswer = listOf(
    IntChoice(0, "不答题"),
    IntChoice(1, "平台答案"),
)

private val submitOffOn = listOf(
    IntChoice(0, "只保存"),
    IntChoice(1, "交卷"),
)

private val xuexitongSubmit = listOf(
    IntChoice(0, "只保存"),
    IntChoice(1, "交卷"),
    IntChoice(2, "无空题才交卷"),
)

val platformCourseConfigById: Map<String, PlatformCourseConfig> = listOf(
    PlatformCourseConfig(
        platformId = Platform.XUEXITONG.id,
        videoModes = xuexitongVideo,
        autoExamModes = xuexitongAnswer,
        submitModes = xuexitongSubmit,
        supportsXuexitongNodeConcurrency = true,
        supportsXuexitongChapterTest = true,
        supportsXuexitongWork = true,
        supportsXuexitongExam = true,
        supportsXuexitongShuffle = true,
    ),
    PlatformCourseConfig(
        platformId = Platform.YINGHUA.id,
        videoModes = yinghuaVideo,
        autoExamModes = aiOrQuestionBank,
        submitModes = submitOffOn,
    ),
    PlatformCourseConfig(
        platformId = Platform.HAIQIKEJI.id,
        videoModes = offNormalFast,
        autoExamModes = aiOrQuestionBank,
        submitModes = submitOffOn,
    ),
    PlatformCourseConfig(
        platformId = Platform.QINGSHUXUETANG.id,
        videoModes = offNormal,
        autoExamModes = platformSolutionAnswer,
        submitModes = submitOffOn,
    ),
    PlatformCourseConfig(platformId = Platform.WELEARN.id, videoModes = welearnVideo, supportsStudyTimeRange = true),
    PlatformCourseConfig(platformId = Platform.ENAEA.id, videoModes = offNormalFast),
    PlatformCourseConfig(platformId = Platform.CQIE.id, videoModes = offNormalFast),
    PlatformCourseConfig(platformId = Platform.KETANGX.id, videoModes = offNormal),
    PlatformCourseConfig(platformId = Platform.TTCDW.id, videoModes = offNormal),
    // 智慧职教: console only supports videoModel 0/1 (off / normal one-shot complete).
    // No work/exam in console, no URL, cookie-based login.
    PlatformCourseConfig(platformId = Platform.ICVE.id, videoModes = offNormal),
).associateBy { it.platformId }

fun platformCourseConfig(platformId: String): PlatformCourseConfig =
    platformCourseConfigById[platformId] ?: PlatformCourseConfig(platformId = platformId, videoModes = offNormal)
