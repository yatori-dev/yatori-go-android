package com.yatori.android.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 刷课任务记录，对应 Go 中的刷课执行状态 */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,           // UUID
    val accountId: Long,
    val platform: String,
    val account: String,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val status: String = TaskStatus.RUNNING.name,
    val totalCourses: Int = 0,
    val doneCourses: Int = 0,
    val totalVideos: Int = 0,
    val doneVideos: Int = 0,
    val errorMessage: String = ""
)

enum class TaskStatus { RUNNING, SUCCESS, FAILED, CANCELLED }
