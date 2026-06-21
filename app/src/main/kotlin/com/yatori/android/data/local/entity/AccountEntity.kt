package com.yatori.android.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.yatori.android.data.local.Converters

@Entity(tableName = "accounts")
@TypeConverters(Converters::class)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val platform: String,
    val url: String = "",
    val remarkName: String = "",
    val account: String,
    val password: String,
    val isProxy: Boolean = false,
    val informEmails: String = "[]",      // JSON array
    val studyTime: String = "10-30",
    val cxNode: Int = 3,
    val cxChapterTestSw: Boolean = true,
    val cxWorkSw: Boolean = true,
    val cxExamSw: Boolean = true,
    val shuffleSw: Boolean = false,
    val videoMode: Int = 1,
    val autoExam: Int = 0,
    val examAutoSubmit: Int = 1,
    val includeCourses: String = "[]",    // JSON array
    val excludeCourses: String = "[]",    // JSON array
    val enabled: Boolean = true
)
