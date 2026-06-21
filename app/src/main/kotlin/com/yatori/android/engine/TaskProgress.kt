package com.yatori.android.engine

import com.yatori.android.domain.model.Account

data class TaskProgress(
    val taskId: String,
    val account: Account,
    val state: BrushState,
    val totalCourses: Int = 0,
    val doneCourses: Int = 0,
    val totalVideos: Int = 0,
    val doneVideos: Int = 0,
    val currentCourse: String = "",
    val currentVideo: String = "",
    val currentPhase: String = "",   // 当前阶段描述，方案C
    val errorMessage: String? = null
)

enum class BrushState { RUNNING, SUCCESS, FAILED, CANCELLED }
