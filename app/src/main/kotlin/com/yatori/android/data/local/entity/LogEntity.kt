package com.yatori.android.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val level: String,          // DEBUG / INFO / WARN / ERROR
    val platform: String = "",
    val account: String = "",
    val message: String,
    val taskId: String = ""
)
