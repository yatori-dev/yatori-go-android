package dev.yatori.mobile.app.ui.courses

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.yatori.mobile.api.dto.MobileConfig
import org.junit.Test
import kotlin.test.assertContains

class CourseRunConfirmationTest {
    @Test
    fun `real xuexitong run warns when final submit is enabled`() {
        val cfg = MobileConfig(
            users = listOf(
                JsonObject().apply {
                    addProperty("accountType", "xuexitong")
                    addProperty("account", "stu")
                    add(
                        "coursesCustom",
                        JsonObject().apply {
                            addProperty("videoModel", 1)
                            addProperty("autoExam", 3)
                            addProperty("examAutoSubmit", 1)
                            add("includeCourses", JsonArray().apply { add("高数") })
                            add("excludeCourses", JsonArray().apply { add("复习") })
                        },
                    )
                },
            ),
        )

        val confirmation = buildCourseRunConfirmation("xuexitong", "stu", cfg, dryRun = false)

        assertContains(confirmation.message, "课程范围：高数")
        assertContains(confirmation.message, "排除课程：复习")
        assertContains(confirmation.message, "会交卷")
    }

    @Test
    fun `dry run confirmation says it will not submit`() {
        val confirmation = buildCourseRunConfirmation("yinghua", "u", MobileConfig(), dryRun = true)

        assertContains(confirmation.title, "试跑")
        assertContains(confirmation.message, "不会自动提交")
    }

    @Test
    fun `xuexitong confirmation hides answer switches when answer mode is off`() {
        val cfg = MobileConfig(
            users = listOf(
                JsonObject().apply {
                    addProperty("accountType", "xuexitong")
                    addProperty("account", "stu")
                    add(
                        "coursesCustom",
                        JsonObject().apply {
                            addProperty("videoModel", 3)
                            addProperty("cxNode", 4)
                            addProperty("autoExam", 0)
                        },
                    )
                },
            ),
        )

        val confirmation = buildCourseRunConfirmation("xuexitong", "stu", cfg, dryRun = false)

        assertContains(confirmation.message, "视频模式：多任务点模式")
        assertContains(confirmation.message, "任务点数量：4")
        kotlin.test.assertFalse(confirmation.message.contains("学习通：章测"))
        kotlin.test.assertFalse(confirmation.message.contains("答题提交："))
    }
}
