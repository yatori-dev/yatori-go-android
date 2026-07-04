package dev.yatori.mobile.app.ui.courses

import dev.yatori.mobile.api.Platform
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
}
