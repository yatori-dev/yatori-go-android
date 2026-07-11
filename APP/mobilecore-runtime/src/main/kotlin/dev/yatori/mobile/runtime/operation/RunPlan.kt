package dev.yatori.mobile.runtime.operation

/**
 * Course selection rule for a (platform, account). Supports generic id/name filters and exact
 * platform-specific matching. A manual rule does NOT skip getCourses; it only filters which
 * synced courses are scheduled for tasks.
 */
data class CourseSelectionRule(
    val mode: Mode = Mode.ALL,
    /** Keywords for [Mode.MANUAL_CONTAINS]; a course matches if its name contains ANY (CI). */
    val includeKeywords: List<String> = emptyList(),
    /** Keywords that always exclude a course by id/name before include matching. */
    val excludeKeywords: List<String> = emptyList(),
    /** Explicit course ids for [Mode.SELECTED]. */
    val selectedCourseIds: List<String> = emptyList(),
) {
    enum class Mode { ALL, SELECTED, MANUAL_CONTAINS, EXACT_NAME, ENAEA_PROJECT_CATEGORY }

    fun matches(
        courseId: String,
        courseName: String,
        projectName: String = "",
        titleTag: String = "",
    ): Boolean {
        if (mode == Mode.ENAEA_PROJECT_CATEGORY) {
            fun pathMatches(path: String): Boolean {
                val parts = path.split("-->", limit = 2).map(String::trim)
                if (parts.size == 1) {
                    return parts[0] == projectName || parts[0] == courseName
                }
                if (parts[0] != projectName) return false
                return parts[1] == titleTag
            }
            if (excludeKeywords.any(::pathMatches)) return false
            return includeKeywords.isEmpty() || includeKeywords.any(::pathMatches)
        }
        if (mode == Mode.EXACT_NAME) {
            if (excludeKeywords.any { it == courseName }) return false
            return includeKeywords.isEmpty() || includeKeywords.any { it == courseName }
        }
        if (excludeKeywords.any { kw -> courseId.contains(kw, ignoreCase = true) || courseName.contains(kw, ignoreCase = true) }) {
            return false
        }
        return when (mode) {
            Mode.ALL -> true
            Mode.SELECTED -> courseId in selectedCourseIds
            Mode.MANUAL_CONTAINS ->
                includeKeywords.isEmpty() ||
                    includeKeywords.any { kw -> courseId.contains(kw, ignoreCase = true) || courseName.contains(kw, ignoreCase = true) }
            Mode.EXACT_NAME -> error("handled above")
            Mode.ENAEA_PROJECT_CATEGORY -> error("handled above")
        }
    }
}

/**
 * A persistable batch course-sync / learn plan. Stored so an interrupted run can be resumed
 * across process death / reboot via WorkManager (see brief §7.4).
 */
data class RunPlan(
    val planId: String,
    val platform: String,
    val account: String,
    val rule: CourseSelectionRule = CourseSelectionRule(),
    val dryRun: Boolean = false,
    val answerPolicy: AnswerPolicy = AnswerPolicy(),
    val xuexitong: XuexitongRunOptions = XuexitongRunOptions(),
    /** 1 = normal (30 s incremental, sequential), 2 = fast (submit 100 % once). Mirrors haiqikeji CoursesCustom.videoModel. */
    val haiqikejiVideoModel: Int = 1,
    /** 0 = off, 1 = normal one-shot completion. Mirrors ketangx CoursesCustom.videoModel. */
    val ketangxVideoModel: Int = 1,
    /** 0 = off, 1 = normal, 2 = violence (fast=true, studyTime=60). Mirrors ENAEA console. */
    val enaeaVideoModel: Int = 1,
    /**
     * 1 = normal (5 s cumulative, sequential), 2 = violence (concurrent goroutines, then auto去红),
     * 3 = 去红 (one-shot submit for red nodes only). Mirrors yinghua CoursesCustom.videoModel.
     */
    val yinghuaVideoModel: Int = 1,
    /** 1 = time-accumulation SCORM loop, 2 = one-shot complete (WeLearnCompletePointAction). */
    val welearnVideoModel: Int = 1,
    /** 1 = 3 s position-window loop, 2 = 秒刷 one-shot submit. Mirrors cqie CoursesCustom.videoModel. */
    val cqieVideoModel: Int = 1,
    /** Task ids already submitted, so a resumed plan does not redo them. */
    val completedTaskIds: List<String> = emptyList(),
)

data class XuexitongRunOptions(
    val videoModel: Int = 1,
    val cxNode: Int = 3,
    val shuffle: Boolean = false,
) {
    val mediaEnabled: Boolean get() = videoModel != 0
    val mode3: Boolean get() = videoModel == 3
    val boundedNodeConcurrency: Int get() = if (cxNode == 0) 3 else cxNode
}

data class AnswerPolicy(
    val enabled: Boolean = false,
    val answerMode: AnswerMode = AnswerMode.DISABLED,
    val runWork: Boolean = true,
    val runExam: Boolean = false,
    val runChapterTest: Boolean = true,
    val submitWorkFinal: Boolean = true,
    val submitExamFinal: Boolean = false,
    val submitChapterTestFinal: Boolean = false,
    val submitFinalWhenComplete: Boolean = false,
    val realSubmitExam: Boolean = false,
    val realSubmitChapterTest: Boolean = false,
    val pauseOnMissingAnswer: Boolean = false,
)
