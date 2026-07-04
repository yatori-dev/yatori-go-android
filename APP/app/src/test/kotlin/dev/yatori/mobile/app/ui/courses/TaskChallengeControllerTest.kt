package dev.yatori.mobile.app.ui.courses

import com.google.gson.JsonObject
import dev.yatori.mobile.api.dto.CourseItem
import dev.yatori.mobile.api.dto.RunTaskResult
import dev.yatori.mobile.api.dto.SessionData
import dev.yatori.mobile.api.dto.TaskItem
import dev.yatori.mobile.runtime.operation.CourseTaskRunner
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskChallengeControllerTest {
    private val session = SessionData("yinghua", "student")
    private val task = TaskItem("chapter-1", name = "Chapter", type = "quiz", platform = "yinghua")

    @Test
    fun `auto solve retries only local OCR format misses and stops after server returns captcha`() = runTest {
        val runner = FakeTaskRunner(
            retryResult = RunTaskResult("yinghua", task.id, "captcha", raw = captchaRaw("next-image")),
        )
        val recognized = listOf<String?>(null, "验证码错", "A1B2")
        var recognizeIndex = 0
        val controller = TaskChallengeController(
            runner,
            CaptchaRecognizer { _, _, _ -> recognized[recognizeIndex++] },
        )
        val pending = controller.capture(
            session,
            task,
            retryOptions = mapOf("action" to "bbsWebPrepare"),
            result = RunTaskResult("yinghua", task.id, "captcha", raw = captchaRaw("first-image")),
        ) ?: error("expected pending challenge")
        val attempts = mutableListOf<Int>()

        val outcome = controller.solveAutomatically(pending) { attempts.add(it) }

        assertTrue(outcome is TaskChallengeOutcome.Manual)
        assertEquals(listOf(1, 2, 3), attempts)
        assertEquals(listOf("A1B2"), runner.submittedCodes)
        assertEquals(listOf("passChapterCaptcha", "bbsWebPrepare"), runner.actions)
        assertEquals("next-image", outcome.pending.challenge.imageBase64)
    }

    @Test
    fun `manual task challenge passes code and replays original action`() = runTest {
        val runner = FakeTaskRunner(
            retryResult = RunTaskResult("yinghua", task.id, "submitted", message = "ok"),
        )
        val controller = TaskChallengeController(
            runner,
            CaptchaRecognizer { _, _, _ -> null },
        )
        val pending = controller.capture(
            session,
            task,
            retryOptions = mapOf("action" to "bbsWebPrepare"),
            result = RunTaskResult("yinghua", task.id, "captcha", raw = captchaRaw("manual-image")),
        ) ?: error("expected pending challenge")

        val outcome = controller.submitManual(pending.id, "A1B2")

        assertTrue(outcome is TaskChallengeOutcome.Success)
        assertEquals("submitted", outcome.result.status)
        assertEquals(listOf("A1B2"), runner.submittedCodes)
        assertEquals(listOf("passChapterCaptcha", "bbsWebPrepare"), runner.actions)
        assertNull(controller.pending())
    }

    @Test
    fun `manual captcha replacement expires old id and continues with new id`() = runTest {
        val runner = FakeTaskRunner(
            retryResults = ArrayDeque(
                listOf(
                    RunTaskResult("yinghua", task.id, "captcha", raw = captchaRaw("second-image")),
                    RunTaskResult("yinghua", task.id, "submitted", message = "ok"),
                ),
            ),
        )
        val controller = TaskChallengeController(
            runner,
            CaptchaRecognizer { _, _, _ -> null },
        )
        val pending = controller.capture(
            session,
            task,
            retryOptions = mapOf("action" to "bbsWebPrepare"),
            result = RunTaskResult("yinghua", task.id, "captcha", raw = captchaRaw("first-image")),
        ) ?: error("expected pending challenge")

        val first = controller.submitManual(pending.id, "A1B2")

        assertTrue(first is TaskChallengeOutcome.Manual)
        assertEquals("second-image", first.pending.challenge.imageBase64)
        assertTrue(controller.submitManual(pending.id, "ZZZZ") is TaskChallengeOutcome.Error)

        val second = controller.submitManual(first.pending.id, "C3D4")

        assertTrue(second is TaskChallengeOutcome.Success)
        assertEquals(listOf("A1B2", "C3D4"), runner.submittedCodes)
        assertNull(controller.pending())
    }

    private class FakeTaskRunner(
        private val retryResult: RunTaskResult? = null,
        private val retryResults: ArrayDeque<RunTaskResult> = ArrayDeque(),
    ) : CourseTaskRunner {
        val actions = mutableListOf<String>()
        val submittedCodes = mutableListOf<String>()

        override suspend fun getCourses(session: SessionData): List<CourseItem> = emptyList()
        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> = emptyList()

        override suspend fun runTask(
            session: SessionData,
            task: TaskItem,
            options: Map<String, Any>,
        ): RunTaskResult {
            val action = options["action"] as? String ?: ""
            actions.add(action)
            if (action == "passChapterCaptcha") {
                submittedCodes.add(options["code"] as? String ?: "")
                return RunTaskResult(session.platform, task.id, "done")
            }
            return if (retryResults.isNotEmpty()) retryResults.removeFirst()
            else retryResult ?: RunTaskResult(session.platform, task.id, "done")
        }
    }

    private companion object {
        fun captchaRaw(image: String): JsonObject =
            JsonObject().apply { addProperty("captchaImage", image) }
    }
}
