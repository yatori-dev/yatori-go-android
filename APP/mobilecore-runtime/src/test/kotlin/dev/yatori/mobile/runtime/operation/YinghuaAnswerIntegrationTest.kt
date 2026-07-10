package dev.yatori.mobile.runtime.operation

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.yatori.mobile.api.dto.CourseItem
import dev.yatori.mobile.api.dto.RunTaskResult
import dev.yatori.mobile.api.dto.SessionData
import dev.yatori.mobile.api.dto.TaskItem
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YinghuaAnswerIntegrationTest {
    private val session = SessionData("yinghua", "student")

    private class AnswerRunner(private val node: TaskItem) : CourseTaskRunner {
        private val course = CourseItem("course-1", name = "测试课程", platform = "yinghua")
        val submitOptions = mutableListOf<Map<String, Any>>()
        var getTasksCalls = 0

        override suspend fun getCourses(session: SessionData): List<CourseItem> = listOf(course)

        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> {
            getTasksCalls += 1
            return listOf(node)
        }

        override suspend fun runTask(
            session: SessionData,
            task: TaskItem,
            options: Map<String, Any>,
        ): RunTaskResult {
            val action = options["action"] as? String ?: error("missing action for ${task.id}")
            return when (action) {
                "pullWork" -> RunTaskResult("yinghua", task.id, "done", raw = JsonObject().apply {
                    add("works", JsonArray().apply { add(paper("workId", "work-1", task)) })
                })
                "pullExam" -> RunTaskResult("yinghua", task.id, "done", raw = JsonObject().apply {
                    add("exams", JsonArray().apply { add(paper("examId", "exam-1", task)) })
                })
                "workQuestions", "examQuestions" -> RunTaskResult("yinghua", task.id, "questions", raw = JsonObject().apply {
                    add("questions", JsonArray().apply { add(question()) })
                })
                "work", "exam" -> {
                    submitOptions.add(options)
                    RunTaskResult("yinghua", task.id, "submitted")
                }
                "workScore", "examScore" -> RunTaskResult("yinghua", task.id, "done", raw = JsonObject().apply {
                    addProperty("score", "100")
                })
                else -> error("unexpected action: ${action}")
            }
        }

        private fun paper(idKey: String, id: String, node: TaskItem) = JsonObject().apply {
            addProperty(idKey, id)
            addProperty("title", id)
            addProperty("courseId", "course-1")
            addProperty("nodeId", node.id)
        }

        private fun question() = JsonObject().apply {
            addProperty("answerId", "answer-1")
            addProperty("index", "1")
            addProperty("type", "单选题")
            addProperty("content", "请选择乙")
            add("options", JsonArray().apply {
                add("甲")
                add("乙")
            })
        }
    }

    private class CapturingPlatformRunner : PlatformTaskRunner {
        val videoModels = mutableListOf<Int>()

        override fun supports(session: SessionData, task: TaskItem): Boolean = true

        override suspend fun runTask(
            session: SessionData,
            task: TaskItem,
            options: PlatformTaskRunOptions,
            shouldCancel: () -> Boolean,
            onEvent: (SyncEvent) -> Unit,
        ): RunTaskResult {
            if (task.id != "keepAlive") videoModels.add(options.videoModel)
            return RunTaskResult("yinghua", task.id, if (task.id == "keepAlive") "done" else "submitted")
        }
    }

    private val answerFactory = AnswerProviderFactory {
        object : AnswerProvider {
            override suspend fun answers(request: AnswerRequest): List<String> = listOf("乙")
        }
    }

    private fun node(
        status: String = "pending",
        progress: Double = 0.0,
        tabVideo: Boolean = true,
        tabWork: Boolean = true,
        tabExam: Boolean = true,
    ) = TaskItem(
        id = "node-1",
        name = "混合节点",
        // Go exposes only one primary type even though all three tab flags can be true.
        type = if (tabExam) "exam" else if (tabWork) "work" else "video",
        status = status,
        progress = progress,
        platform = "yinghua",
        raw = JsonObject().apply {
            addProperty("nodeId", "node-1")
            addProperty("courseId", "course-1")
            addProperty("tabVideo", tabVideo)
            addProperty("tabWork", tabWork)
            addProperty("tabExam", tabExam)
        },
    )

    private fun policy(finalSubmit: Boolean = false) = AnswerPolicy(
        enabled = true,
        answerMode = AnswerMode.HOST_AI,
        runWork = true,
        runExam = true,
        submitWorkFinal = finalSubmit,
        submitExamFinal = finalSubmit,
        realSubmitExam = finalSubmit,
    )

    @Test
    fun completedMixedNodeStillSavesWorkAndExamWhenVideoIsDisabled() = runTest {
        val runner = AnswerRunner(node(status = "completed", progress = 100.0))
        val platform = CapturingPlatformRunner()
        val manager = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            platformRunner = platform,
            answerProviderFactory = answerFactory,
        )

        manager.run(session, RunPlan("yinghua-answer-only", "yinghua", "student", answerPolicy = policy(), yinghuaVideoModel = 0))

        assertTrue(platform.videoModels.isEmpty(), "videoModel=0 must not run the completed video")
        assertEquals(listOf("work", "exam"), runner.submitOptions.map { it["action"] })
        assertFalse(runner.submitOptions[0]["finalize"] as Boolean)
        assertFalse(runner.submitOptions[1]["finalize"] as Boolean)
        assertEquals(true, runner.submitOptions[1]["realSubmit"], "exam answers must be saved with finish=0")
    }

    @Test
    fun violenceModeRunsVideoAndAnswersEachNodeOnlyOnce() = runTest {
        val runner = AnswerRunner(node(tabExam = false))
        val platform = CapturingPlatformRunner()
        val manager = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            platformRunner = platform,
            answerProviderFactory = answerFactory,
        )

        manager.run(session, RunPlan("yinghua-violence-answer", "yinghua", "student", answerPolicy = policy(), yinghuaVideoModel = 2))

        assertEquals(listOf(2), platform.videoModels)
        assertEquals(listOf("work"), runner.submitOptions.map { it["action"] })
        assertEquals(2, runner.getTasksCalls, "initial pass plus auto-red verification")
    }

    @Test
    fun redModeAnswersWorkAndExamEvenWhenThereIsNoRedVideo() = runTest {
        val runner = AnswerRunner(node(status = "completed", progress = 100.0, tabVideo = false))
        val platform = CapturingPlatformRunner()
        val manager = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            platformRunner = platform,
            answerProviderFactory = answerFactory,
        )

        manager.run(session, RunPlan("yinghua-red-answer", "yinghua", "student", answerPolicy = policy(finalSubmit = true), yinghuaVideoModel = 3))

        assertTrue(platform.videoModels.isEmpty())
        assertEquals(listOf("work", "exam"), runner.submitOptions.map { it["action"] })
        assertTrue(runner.submitOptions.all { it["finalize"] == true })
        assertEquals(true, runner.submitOptions.last()["realSubmit"])
    }
}
