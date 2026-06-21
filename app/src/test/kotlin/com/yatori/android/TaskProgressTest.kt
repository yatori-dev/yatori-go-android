package com.yatori.android

import com.yatori.android.engine.BrushState
import com.yatori.android.engine.TaskProgress
import com.yatori.android.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class TaskProgressTest {
    private val acc = Account(platform = Platform.YINGHUA, account = "test", password = "pw")

    @Test fun `progress ratio is correct`() {
        val p = TaskProgress("id", acc, BrushState.RUNNING, totalCourses = 10, doneCourses = 5)
        assertEquals(0.5f, p.doneCourses.toFloat() / p.totalCourses, 0.001f)
    }

    @Test fun `zero total does not divide by zero`() {
        val p = TaskProgress("id", acc, BrushState.RUNNING, totalCourses = 0, doneCourses = 0)
        val ratio = if (p.totalCourses > 0) p.doneCourses.toFloat() / p.totalCourses else 0f
        assertEquals(0f, ratio, 0.001f)
    }
}
