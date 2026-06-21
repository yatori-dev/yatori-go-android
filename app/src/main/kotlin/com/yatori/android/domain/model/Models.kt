package com.yatori.android.domain.model

/** 账号信息领域模型（对应 Go config.User） */
data class Account(
    val id: Long = 0,
    val platform: Platform,
    val url: String = "",
    val remarkName: String = "",
    val account: String,
    val password: String,
    val isProxy: Boolean = false,
    val informEmails: List<String> = emptyList(),
    val coursesCustom: CoursesCustom = CoursesCustom(),
    val enabled: Boolean = true
) {
    val displayName: String get() = remarkName.ifBlank { account }
}

/** 课程自定义配置（对应 Go config.CoursesCustom） */
data class CoursesCustom(
    val studyTime: String = "10-30",
    val cxNode: Int = 3,
    val cxChapterTestSw: Boolean = true,
    val cxWorkSw: Boolean = true,
    val cxExamSw: Boolean = true,
    val shuffleSw: Boolean = false,
    val videoMode: VideoMode = VideoMode.NORMAL,
    val autoExam: ExamMode = ExamMode.OFF,
    val examAutoSubmit: Int = 1,
    val includeCourses: List<String> = emptyList(),
    val excludeCourses: List<String> = emptyList()
)

/** 全局设置（对应 Go config.Setting） */
data class AppSettings(
    val logLevel: String = "INFO",
    val logModel: Int = 0,
    val emailSw: Boolean = false,
    val smtpHost: String = "",
    val smtpPort: Int = 465,
    val smtpUser: String = "",
    val smtpPassword: String = "",
    val aiType: AiProvider = AiProvider.TONGYI,
    val aiUrl: String = "",
    val aiModel: String = "",
    val aiApiKey: String = "",
    val apiQueUrl: String = "http://localhost:8083",
    val darkMode: Boolean = false,
    val followSystem: Boolean = true
)
