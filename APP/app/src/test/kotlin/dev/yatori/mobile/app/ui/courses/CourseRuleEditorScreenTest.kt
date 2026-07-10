package dev.yatori.mobile.app.ui.courses

import com.google.gson.JsonObject
import dev.yatori.mobile.api.Platform
import dev.yatori.mobile.api.dto.CourseItem
import dev.yatori.mobile.app.platform.platformCourseConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CourseRuleEditorScreenTest {
    @Test
    fun `xuexitong video modes use user-facing names`() {
        val modes = platformCourseConfig(Platform.XUEXITONG.id).videoModes.associate { it.value to it.label }

        assertEquals("普通模式", modes[1])
        assertEquals("多课程模式", modes[2])
        assertEquals("多任务点模式", modes[3])
    }

    @Test
    fun `cxNode only accepts positive numbers`() {
        assertEquals(1, positiveCxNodeOrNull("1"))
        assertEquals(8, positiveCxNodeOrNull(" 8 "))
        assertNull(positiveCxNodeOrNull("0"))
        assertNull(positiveCxNodeOrNull("-1"))
        assertNull(positiveCxNodeOrNull(""))
    }

    @Test
    fun `xuexitong mode3 remains available`() {
        val mode3 = platformCourseConfig(Platform.XUEXITONG.id).videoModes.firstOrNull { it.value == 3 }

        assertNotNull(mode3)
        assertEquals("多任务点模式", mode3.label)
    }

    @Test
    fun `haiqikeji answer modes include external question bank`() {
        val modes = platformCourseConfig(Platform.HAIQIKEJI.id).autoExamModes.associate { it.value to it.label }

        assertEquals("不答题", modes[0])
        assertEquals("AI 答题", modes[1])
        assertEquals("题库答题", modes[2])
    }

    @Test
    fun `enaea selection values use distinct project category paths`() {
        fun course(id: String, project: String, category: String, name: String) = CourseItem(
            id = id,
            name = name,
            platform = "enaea",
            raw = JsonObject().apply {
                addProperty("projectName", project)
                addProperty("titleTag", category)
            },
        )

        val values = courseSelectionValues(
            "enaea",
            listOf(
                course("c1", "项目A", "必修", "课程一"),
                course("c2", "项目A", "必修", "课程二"),
                course("c3", "项目A", "选修", "课程三"),
            ),
        )

        assertEquals(listOf("项目A-->必修", "项目A-->选修"), values)
    }

    @Test
    fun `other platforms continue selecting by course name`() {
        val values = courseSelectionValues(
            "yinghua",
            listOf(CourseItem("c1", name = "课程一", platform = "yinghua")),
        )

        assertEquals(listOf("课程一"), values)
    }
}
