package dev.yatori.mobile.app.ui.courses

import org.junit.Test
import kotlin.test.assertEquals

class CourseCacheSummaryTest {
    @Test
    fun `course cache summary distinguishes empty synced cache from never synced`() {
        assertEquals("未同步课程", courseCacheSummaryText(hasCourseCache = false, cachedCount = 0))
        assertEquals("没有进行中的课程", courseCacheSummaryText(hasCourseCache = true, cachedCount = 0))
        assertEquals("已同步 2 门课程", courseCacheSummaryText(hasCourseCache = true, cachedCount = 2))
    }
}
