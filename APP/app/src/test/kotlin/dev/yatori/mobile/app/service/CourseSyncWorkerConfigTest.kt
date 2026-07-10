package dev.yatori.mobile.app.service

import com.google.gson.JsonObject
import dev.yatori.mobile.runtime.operation.AnswerMode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CourseSyncWorkerConfigTest {
    @Test
    fun `xuexitong run options preserve mode3 concurrency config`() {
        val cc = JsonObject().apply {
            addProperty("videoModel", 3)
            addProperty("cxNode", 5)
            addProperty("shuffleSw", 1)
        }

        val options = xuexitongOptionsFromCoursesCustom(cc)

        assertEquals(3, options.videoModel)
        assertEquals(5, options.cxNode)
        assertTrue(options.shuffle)
        assertTrue(options.mode3)
        assertEquals(5, options.boundedNodeConcurrency)
    }

    @Test
    fun `xuexitong run options use console defaults`() {
        val options = xuexitongOptionsFromCoursesCustom(JsonObject())

        assertEquals(1, options.videoModel)
        assertEquals(3, options.cxNode)
        assertFalse(options.shuffle)
    }

    @Test
    fun `xuexitong run options reject non-positive cxNode from old configs`() {
        val negative = xuexitongOptionsFromCoursesCustom(JsonObject().apply { addProperty("cxNode", -1) })
        val zero = xuexitongOptionsFromCoursesCustom(JsonObject().apply { addProperty("cxNode", 0) })

        assertEquals(3, negative.cxNode)
        assertEquals(3, zero.cxNode)
    }

    @Test
    fun `haiqikeji external question bank config enables generic answer policy`() {
        val policy = answerPolicyFromCoursesCustom(
            "haiqikeji",
            JsonObject().apply {
                addProperty("autoExam", 2)
                addProperty("examAutoSubmit", 0)
            },
        )

        assertTrue(policy.enabled)
        assertEquals(AnswerMode.EXTERNAL_QUESTION_BANK, policy.answerMode)
        assertTrue(policy.runWork)
        assertTrue(policy.runExam)
        assertFalse(policy.runChapterTest)
        assertFalse(policy.submitWorkFinal)
        assertFalse(policy.submitExamFinal)
        assertFalse(policy.realSubmitExam)
    }

    @Test
    fun `xuexitong automatic submit mode authorizes final exam submit`() {
        val policy = answerPolicyFromCoursesCustom(
            "xuexitong",
            JsonObject().apply {
                addProperty("autoExam", 3)
                addProperty("examAutoSubmit", 1)
            },
        )

        assertTrue(policy.submitExamFinal)
        assertFalse(policy.submitFinalWhenComplete)
        assertTrue(policy.realSubmitExam)
    }

    @Test
    fun `xuexitong complete-only mode authorizes submit only after completeness check`() {
        val policy = answerPolicyFromCoursesCustom(
            "xuexitong",
            JsonObject().apply {
                addProperty("autoExam", 3)
                addProperty("examAutoSubmit", 2)
            },
        )

        assertFalse(policy.submitExamFinal)
        assertTrue(policy.submitFinalWhenComplete)
        assertTrue(policy.realSubmitExam)
    }
    @Test
    fun `non xuexitong platforms reject xuexitong built in ai mode`() {
        val policy = answerPolicyFromCoursesCustom(
            "haiqikeji",
            JsonObject().apply { addProperty("autoExam", 3) },
        )

        assertFalse(policy.enabled)
    }
}
