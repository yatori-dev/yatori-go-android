package dev.yatori.mobile.app.ui.courses

import org.junit.Test
import kotlin.test.assertEquals

class CourseCacheSummaryTest {
    @Test
    fun `qingshuxuetang score summary formats all reference dimensions`() {
        val raw = com.google.gson.JsonObject().apply {
            addProperty("coursewareLearnGainScore", 10)
            addProperty("coursewareLearnTotalScore", 30)
            addProperty("courseWorkGainScore", 5)
            addProperty("courseWorkTotalScore", 20)
            addProperty("courseMaterialsLearnGainScore", 3)
            addProperty("courseMaterialsLearnTotalScore", 15)
        }

        assertEquals("课件 10/30 · 作业 5/20 · 资料 3/15", qingshuxuetangScoreSummary(raw))
    }

    @Test
    fun `qingshuxuetang score summary is absent for unrelated raw data`() {
        assertEquals(null, qingshuxuetangScoreSummary(com.google.gson.JsonObject()))
    }

    @Test
    fun `course cache summary distinguishes empty synced cache from never synced`() {
        assertEquals("未同步课程", courseCacheSummaryText(hasCourseCache = false, cachedCount = 0))
        assertEquals("没有进行中的课程", courseCacheSummaryText(hasCourseCache = true, cachedCount = 0))
        assertEquals("已同步 2 门课程", courseCacheSummaryText(hasCourseCache = true, cachedCount = 2))
    }
}
