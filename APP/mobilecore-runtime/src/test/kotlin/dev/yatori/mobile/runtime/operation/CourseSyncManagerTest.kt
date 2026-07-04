package dev.yatori.mobile.runtime.operation

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.yatori.mobile.api.dto.CourseItem
import dev.yatori.mobile.api.dto.RunTaskResult
import dev.yatori.mobile.api.dto.SessionData
import dev.yatori.mobile.api.dto.TaskItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CourseSyncManagerTest {

    private val session = SessionData("yinghua", "stu")

    private fun course(id: String, name: String) = CourseItem(id, name = name, platform = "yinghua")
    private fun task(id: String, status: String = "not_started", progress: Double = 0.0) =
        TaskItem(id, name = "task-$id", status = status, progress = progress, platform = "yinghua")

    private class FakeRunner(
        val courses: List<CourseItem>,
        val tasksByCourse: Map<String, List<TaskItem>>,
    ) : CourseTaskRunner {
        val submitted = mutableListOf<String>()
        override suspend fun getCourses(session: SessionData) = courses
        override suspend fun getTasks(session: SessionData, course: CourseItem) =
            tasksByCourse[course.id] ?: emptyList()
        override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult {
            submitted.add(task.id)
            return RunTaskResult("yinghua", task.id, if (options["dryRun"] == true) "dry_run" else "submitted")
        }
    }

    private class FakePlatformRunner : PlatformTaskRunner {
        val submitted = mutableListOf<String>()
        override fun supports(session: SessionData, task: TaskItem): Boolean = session.platform == "haiqikeji"
        override suspend fun runTask(
            session: SessionData,
            task: TaskItem,
            options: PlatformTaskRunOptions,
            shouldCancel: () -> Boolean,
            onEvent: (SyncEvent) -> Unit,
        ): RunTaskResult {
            submitted.add(task.id)
            onEvent(SyncEvent("info", "platform-runner ${task.id}", session.platform))
            return RunTaskResult(session.platform, task.id, if (options.dryRun) "dry_run" else "submitted")
        }
    }

    @Test
    fun `submits all unfinished tasks across courses`() = runTest {
        val runner = FakeRunner(
            courses = listOf(course("c1", "math"), course("c2", "english")),
            tasksByCourse = mapOf(
                "c1" to listOf(task("t1"), task("t2", status = "completed")),
                "c2" to listOf(task("t3"), task("t4", progress = 100.0)),
            ),
        )
        val controller = OperationController(now = { 0L })
        val mgr = CourseSyncManager(runner, controller)
        val events = mutableListOf<SyncEvent>()

        val submitted = mgr.run(session, RunPlan("plan1", "yinghua", "stu"), onEvent = { events.add(it) })

        assertEquals(listOf("t1", "t3"), submitted)
        assertEquals(OperationStatus.DONE, controller.operations.value.single().status)
        assertTrue(events.any { it.message.contains("t1") && it.message.contains("submitted") })
    }

    @Test
    fun `manual contains rule filters courses`() = runTest {
        val runner = FakeRunner(
            courses = listOf(course("c1", "advanced math"), course("c2", "college english")),
            tasksByCourse = mapOf("c1" to listOf(task("t1")), "c2" to listOf(task("t2"))),
        )
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))
        val plan = RunPlan(
            "plan2", "yinghua", "stu",
            rule = CourseSelectionRule(CourseSelectionRule.Mode.MANUAL_CONTAINS, includeKeywords = listOf("math")),
        )
        val submitted = mgr.run(session, plan)
        assertEquals(listOf("t1"), submitted)
    }

    @Test
    fun `completedTaskIds are not resubmitted on resume`() = runTest {
        val runner = FakeRunner(
            courses = listOf(course("c1", "math")),
            tasksByCourse = mapOf("c1" to listOf(task("t1"), task("t2"))),
        )
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))
        val plan = RunPlan("plan3", "yinghua", "stu", completedTaskIds = listOf("t1"))
        val submitted = mgr.run(session, plan)
        assertEquals(listOf("t2"), submitted)
    }

    @Test
    fun `dryRun passes the option through`() = runTest {
        val runner = FakeRunner(
            courses = listOf(course("c1", "math")),
            tasksByCourse = mapOf("c1" to listOf(task("t1"))),
        )
        val events = mutableListOf<SyncEvent>()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))
        mgr.run(session, RunPlan("plan4", "yinghua", "stu", dryRun = true), onEvent = { events.add(it) })
        assertTrue(events.any { it.message.contains("dry_run") })
    }

    @Test
    fun `uses platform runner for supported host-driven tasks`() = runTest {
        val hqSession = SessionData("haiqikeji", "stu")
        val hqTask = TaskItem("node1", name = "video", type = "video", platform = "haiqikeji")
        val runner = FakeRunner(
            courses = listOf(CourseItem("c1", name = "course", platform = "haiqikeji")),
            tasksByCourse = mapOf("c1" to listOf(hqTask)),
        )
        val platformRunner = FakePlatformRunner()
        val events = mutableListOf<SyncEvent>()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }), platformRunner)

        val submitted = mgr.run(hqSession, RunPlan("plan5", "haiqikeji", "stu"), onEvent = { events.add(it) })

        assertEquals(listOf("node1"), submitted)
        assertEquals(listOf("node1"), platformRunner.submitted)
        assertTrue(runner.submitted.isEmpty(), "generic runTask should not be used for supported platform tasks")
        assertTrue(events.any { it.message.contains("platform-runner") })
    }

    @Test
    fun `generic task captcha is handed to resolver and original task is counted`() = runTest {
        val challengeRaw = JsonObject().apply { addProperty("captchaImage", "base64") }
        val runner = object : CourseTaskRunner {
            override suspend fun getCourses(session: SessionData): List<CourseItem> =
                listOf(course("c1", "math"))

            override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> =
                listOf(task("t1"))

            override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult =
                RunTaskResult("yinghua", task.id, "captcha", raw = challengeRaw)
        }
        var request: TaskChallengeRequest? = null
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            taskChallengeResolver = TaskChallengeResolver {
                request = it
                RunTaskResult(it.session.platform, it.task.id, "submitted")
            },
        )
        val events = mutableListOf<SyncEvent>()

        val submitted = mgr.run(session, RunPlan("plan-captcha", "yinghua", "stu"), onEvent = { events.add(it) })

        assertEquals(listOf("t1"), submitted)
        assertEquals("t1", request?.task?.id)
        assertEquals("captcha", request?.result?.status)
        assertTrue(events.any { it.message.contains("requires captcha") })
    }

    @Test
    fun `cancelling during captcha resolver marks operation cancelled instead of failed`() = runTest {
        val challengeRaw = JsonObject().apply { addProperty("captchaImage", "base64") }
        val runner = object : CourseTaskRunner {
            override suspend fun getCourses(session: SessionData): List<CourseItem> =
                listOf(course("c1", "math"))

            override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> =
                listOf(task("t1"))

            override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult =
                RunTaskResult("yinghua", task.id, "captcha", raw = challengeRaw)
        }
        val controller = OperationController(now = { 0L })
        val mgr = CourseSyncManager(
            runner,
            controller,
            taskChallengeResolver = TaskChallengeResolver {
                controller.cancel(controller.operations.value.single().id)
                throw CancellationException("manual captcha cancelled")
            },
        )

        val submitted = mgr.run(session, RunPlan("plan-captcha-cancel", "yinghua", "stu"))

        assertEquals(emptyList(), submitted)
        val op = controller.operations.value.single()
        assertEquals(OperationStatus.DONE, op.status)
        assertTrue(op.detail.contains("cancelled"))
    }

    @Test
    fun `course selection excludes configured keywords before include matching`() {
        val rule = CourseSelectionRule(
            mode = CourseSelectionRule.Mode.MANUAL_CONTAINS,
            includeKeywords = listOf("数学"),
            excludeKeywords = listOf("复习"),
        )

        assertTrue(rule.matches("c1", "高等数学"))
        assertFalse(rule.matches("c2", "高等数学复习课"))
        assertFalse(rule.matches("c3", "大学英语"))
    }

    @Test
    fun `xuexitong course run keeps chapter order then runs course work and exam`() = runTest {
        val runner = XuexitongFlowRunner()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))
        val plan = RunPlan(
            planId = "xxt-plan",
            platform = "xuexitong",
            account = "stu",
            answerPolicy = AnswerPolicy(
                enabled = true,
                answerMode = AnswerMode.XUEXITONG_BUILT_IN,
                runWork = true,
                runExam = true,
                runChapterTest = true,
                submitWorkFinal = true,
                submitExamFinal = true,
                submitChapterTestFinal = true,
                realSubmitExam = false,
                realSubmitChapterTest = false,
            ),
        )

        val submitted = mgr.run(SessionData("xuexitong", "stu"), plan)

        assertEquals(listOf("video-1", "quiz-1", "doc-1", "bbs-1"), submitted)
        assertEquals(
            listOf(
                "detail:course-1",
                "tasks:node-1",
                "tasks:node-2",
                "run:video-1:none",
                "run:quiz-1:pullChapterTest",
                "run:course-1:xxtAI",
                "run:quiz-1:chapterTest:false",
                "run:doc-1:none",
                "run:bbs-1:bbsPrepare",
                "run:course-1:xxtAI",
                "run:bbs-1:bbs:true",
                "run:xxt-work-list:pullWorkList",
                "run:work-1:enterWork",
                "run:work-answer:workQuestion",
                "run:course-1:xxtAI",
                "run:work-answer:work:true",
                "run:xxt-exam-list:pullExamList",
                "run:exam-1:enterExam",
                "run:exam-answer:examQuestion",
                "run:course-1:xxtAI",
                "run:exam-answer:exam:false",
            ),
            runner.calls,
        )
    }

    @Test
    fun `xuexitong work uses fallback answer when built in ai is empty`() = runTest {
        val runner = XuexitongFallbackRunner()
        val controller = OperationController(now = { 0L })
        val mgr = CourseSyncManager(runner, controller)
        val events = mutableListOf<SyncEvent>()
        val plan = RunPlan(
            planId = "xxt-fallback",
            platform = "xuexitong",
            account = "stu",
            answerPolicy = AnswerPolicy(
                enabled = true,
                answerMode = AnswerMode.XUEXITONG_BUILT_IN,
                runWork = true,
                runExam = false,
                runChapterTest = false,
                submitWorkFinal = false,
                submitFinalWhenComplete = true,
            ),
        )

        mgr.run(SessionData("xuexitong", "stu"), plan, onEvent = { events.add(it) })

        assertEquals(listOf("Option A"), runner.submittedAnswers)
        assertEquals(listOf(true), runner.submittedFinals)
        assertTrue(events.any { it.message.contains("used fallback answer") })
        val history = controller.questionHistory.value.single()
        assertEquals(QuestionHistoryStatus.SUBMITTED, history.status)
        assertEquals("fallback", history.answerSource)
        assertEquals("Option A", history.answerPreview)
        assertTrue(history.finalSubmit)
    }

    @Test
    fun `xuexitong complete-only final submit saves when fallback answer is blank`() = runTest {
        val runner = XuexitongFallbackRunner(fillBlank = true)
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))
        val plan = RunPlan(
            planId = "xxt-fallback-blank",
            platform = "xuexitong",
            account = "stu",
            answerPolicy = AnswerPolicy(
                enabled = true,
                answerMode = AnswerMode.XUEXITONG_BUILT_IN,
                runWork = true,
                runExam = false,
                runChapterTest = false,
                submitWorkFinal = false,
                submitFinalWhenComplete = true,
            ),
        )

        mgr.run(SessionData("xuexitong", "stu"), plan)

        assertEquals(listOf(""), runner.submittedAnswers)
        assertEquals(listOf(false), runner.submittedFinals)
    }

    @Test
    fun `blank fallback answer can be edited by host before submit`() = runTest {
        val runner = XuexitongFallbackRunner(fillBlank = true)
        var request: AnswerEditRequest? = null
        val controller = OperationController(now = { 0L })
        val mgr = CourseSyncManager(
            runner,
            controller,
            answerEditResolver = AnswerEditResolver {
                request = it
                listOf("Edited Answer")
            },
        )
        val events = mutableListOf<SyncEvent>()
        val plan = RunPlan(
            planId = "xxt-answer-edit",
            platform = "xuexitong",
            account = "stu",
            answerPolicy = AnswerPolicy(
                enabled = true,
                answerMode = AnswerMode.XUEXITONG_BUILT_IN,
                runWork = true,
                runExam = false,
                runChapterTest = false,
                submitWorkFinal = false,
                submitFinalWhenComplete = true,
            ),
        )

        mgr.run(SessionData("xuexitong", "stu"), plan, onEvent = { events.add(it) })

        assertEquals("work", request?.scope)
        assertEquals(listOf(""), request?.suggestedAnswers)
        assertEquals(listOf("Edited Answer"), runner.submittedAnswers)
        assertEquals(listOf(true), runner.submittedFinals)
        assertTrue(events.any { it.message.contains("awaiting manual answer edit") })
        assertTrue(controller.questionHistory.value.any { it.status == QuestionHistoryStatus.WAITING_EDIT })
        val history = controller.questionHistory.value.single { it.status == QuestionHistoryStatus.SUBMITTED }
        assertEquals(QuestionHistoryStatus.SUBMITTED, history.status)
        assertEquals("manual", history.answerSource)
        assertEquals("Edited Answer", history.answerPreview)
    }

    @Test
    fun `provider answer does not open manual answer edit`() = runTest {
        val runner = XuexitongFallbackRunner(fillBlank = true)
        val provider = FakeAnswerProvider(listOf("Provider Answer"))
        var editOpened = false
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            answerProviderFactory = AnswerProviderFactory { provider },
            answerEditResolver = AnswerEditResolver {
                editOpened = true
                listOf("Edited Answer")
            },
        )

        mgr.run(SessionData("xuexitong", "stu"), answerPlan(AnswerMode.HOST_AI))

        assertFalse(editOpened)
        assertEquals(listOf("Provider Answer"), runner.submittedAnswers)
    }

    @Test
    fun `blank bbs content can be edited by host before submit`() = runTest {
        val runner = XuexitongBbsRunner()
        val provider = FakeAnswerProvider(emptyList())
        var request: AnswerEditRequest? = null
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            answerProviderFactory = AnswerProviderFactory { provider },
            answerEditResolver = AnswerEditResolver {
                request = it
                listOf("Edited BBS reply")
            },
        )
        val events = mutableListOf<SyncEvent>()

        val submitted = mgr.run(SessionData("xuexitong", "stu"), bbsPlan(), onEvent = { events.add(it) })

        assertEquals(listOf("bbs-1"), submitted)
        assertEquals("bbs", request?.scope)
        assertEquals(listOf(""), request?.suggestedAnswers)
        assertEquals("Edited BBS reply", runner.submittedContent.single())
        assertTrue(runner.realSubmit.single())
        assertTrue(events.any { it.message.contains("awaiting content edit") })
    }

    @Test
    fun `provider bbs content does not open manual edit`() = runTest {
        val runner = XuexitongBbsRunner()
        val provider = FakeAnswerProvider(listOf("Provider BBS reply"))
        var editOpened = false
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            answerProviderFactory = AnswerProviderFactory { provider },
            answerEditResolver = AnswerEditResolver {
                editOpened = true
                listOf("Edited BBS reply")
            },
        )

        mgr.run(SessionData("xuexitong", "stu"), bbsPlan())

        assertFalse(editOpened)
        assertEquals("Provider BBS reply", runner.submittedContent.single())
    }

    @Test
    fun `autoExam host ai mode routes to host provider`() = runTest {
        val runner = XuexitongFallbackRunner()
        val host = FakeAnswerProvider(listOf("Host Answer"))
        val external = FakeAnswerProvider(listOf("External Answer"))
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            answerProviderFactory = CompositeAnswerProviderFactory(
                builtIn = BuiltInXuexitongAnswerProvider(runner),
                hostAi = host,
                external = external,
            ),
        )

        mgr.run(SessionData("xuexitong", "stu"), answerPlan(AnswerMode.HOST_AI))

        assertEquals(listOf("Host Answer"), runner.submittedAnswers)
        assertEquals(1, host.answerCalls)
        assertEquals(0, external.answerCalls)
    }

    @Test
    fun `autoExam external bank mode routes to external provider`() = runTest {
        val runner = XuexitongFallbackRunner()
        val host = FakeAnswerProvider(listOf("Host Answer"))
        val external = FakeAnswerProvider(listOf("External Answer"))
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            answerProviderFactory = CompositeAnswerProviderFactory(
                builtIn = BuiltInXuexitongAnswerProvider(runner),
                hostAi = host,
                external = external,
            ),
        )

        mgr.run(SessionData("xuexitong", "stu"), answerPlan(AnswerMode.EXTERNAL_QUESTION_BANK))

        assertEquals(listOf("External Answer"), runner.submittedAnswers)
        assertEquals(0, host.answerCalls)
        assertEquals(1, external.answerCalls)
    }

    @Test
    fun `haiqikeji external bank mode answers and submits work`() = runTest {
        val runner = HaiqikejiAnswerRunner("work")
        val external = FakeAnswerProvider(listOf("Option B"))
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            answerProviderFactory = AnswerProviderFactory { mode ->
                if (mode == AnswerMode.EXTERNAL_QUESTION_BANK) external else null
            },
        )

        val submitted = mgr.run(
            SessionData("haiqikeji", "stu"),
            haiqikejiAnswerPlan(scope = "work", submitFinal = false),
        )

        assertEquals(listOf("node-work"), submitted)
        assertEquals(listOf("pullWork", "workQuestions", "work"), runner.actions)
        assertEquals(1, external.answerCalls)
        assertEquals("single", external.requests.single().question.get("type").asString)
        assertEquals("single", external.requests.single().question.get("typeCode").asString)
        val payload = runner.submittedAnswers.single()
        assertEquals("topic-1", payload["topicId"])
        assertEquals("record-1", payload["recordId"])
        assertEquals("wr-1", payload["wrId"])
        assertEquals("wa-1", payload["waId"])
        assertEquals(1, payload["type"])
        assertEquals(listOf("Option A", "Option B"), payload["options"])
        assertEquals(listOf("A", "B"), payload["optionIdx"])
        assertEquals(listOf("Option B"), payload["answers"])
    }

    @Test
    fun `haiqikeji exam keeps real submit disabled unless policy allows it`() = runTest {
        val runner = HaiqikejiAnswerRunner("exam")
        val external = FakeAnswerProvider(listOf("Option A"))
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            answerProviderFactory = AnswerProviderFactory { mode ->
                if (mode == AnswerMode.EXTERNAL_QUESTION_BANK) external else null
            },
        )

        mgr.run(
            SessionData("haiqikeji", "stu"),
            haiqikejiAnswerPlan(scope = "exam", submitFinal = true, realSubmitExam = false),
        )

        assertEquals(listOf("pullExam", "examQuestions", "exam"), runner.actions)
        assertEquals(listOf(false), runner.realSubmitOptions)
        assertEquals(listOf("Option A"), runner.submittedAnswers.single()["answers"])
    }

    @Test
    fun `autoExam xuexitong built in mode still calls xxtAI action`() = runTest {
        val runner = XuexitongFlowRunner()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))

        mgr.run(SessionData("xuexitong", "stu"), answerPlan(AnswerMode.XUEXITONG_BUILT_IN))

        assertTrue(runner.calls.any { it == "run:course-1:xxtAI" })
    }

    @Test
    fun `xuexitong video model zero skips media tasks but keeps answer tasks`() = runTest {
        val runner = XuexitongFlowRunner()
        val mgr = CourseSyncManager(runner, OperationController(now = { 0L }))
        val plan = RunPlan(
            planId = "xxt-video-off",
            platform = "xuexitong",
            account = "stu",
            answerPolicy = AnswerPolicy(
                enabled = true,
                answerMode = AnswerMode.XUEXITONG_BUILT_IN,
                runWork = false,
                runExam = false,
                runChapterTest = true,
            ),
            xuexitong = XuexitongRunOptions(videoModel = 0),
        )

        val submitted = mgr.run(SessionData("xuexitong", "stu"), plan)

        assertEquals(listOf("quiz-1", "bbs-1"), submitted)
        assertFalse(runner.calls.any { it == "run:video-1:none" })
        assertFalse(runner.calls.any { it == "run:doc-1:none" })
    }

    @Test
    fun `xuexitong mode3 builds bounded relogin pool and runs node tasks`() = runTest {
        val runner = XuexitongFlowRunner()
        val relogins = mutableListOf<Int>()
        val mgr = CourseSyncManager(
            runner,
            OperationController(now = { 0L }),
            xuexitongSessionProvider = XuexitongSessionProvider { primary, index ->
                relogins.add(index)
                primary.copy(cookies = "pool-$index")
            },
            mode3LoginSpacingMillis = 0,
            sleepMillis = {},
        )
        val plan = RunPlan(
            planId = "xxt-mode3",
            platform = "xuexitong",
            account = "stu",
            answerPolicy = AnswerPolicy(enabled = false),
            xuexitong = XuexitongRunOptions(videoModel = 3, cxNode = 2),
        )

        val submitted = mgr.run(SessionData("xuexitong", "stu", cookies = "primary"), plan)

        assertEquals(listOf(1), relogins)
        assertEquals(setOf("video-1", "doc-1"), submitted.toSet())
        assertTrue(runner.calls.contains("run:video-1:none"))
        assertTrue(runner.calls.contains("run:doc-1:none"))
    }

    private class XuexitongFlowRunner : CourseTaskRunner {
        val calls = mutableListOf<String>()
        private val course = CourseItem("course-1", name = "Course", platform = "xuexitong", raw = ctx())
        private val node1 = CourseItem("node-1", name = "Node 1", platform = "xuexitong", raw = ctx())
        private val node2 = CourseItem("node-2", name = "Node 2", platform = "xuexitong", raw = ctx())

        override suspend fun getCourses(session: SessionData): List<CourseItem> = listOf(course)

        override suspend fun getCourseDetail(session: SessionData, course: CourseItem): List<CourseItem> {
            calls.add("detail:${course.id}")
            return listOf(node1, node2)
        }

        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> {
            calls.add("tasks:${course.id}")
            return when (course.id) {
                "node-1" -> listOf(
                    TaskItem("video-1", name = "Video", type = "video", platform = "xuexitong", raw = ctx()),
                    TaskItem("quiz-1", name = "Quiz", type = "quiz", platform = "xuexitong", raw = ctx()),
                )
                "node-2" -> listOf(
                    TaskItem("doc-1", name = "Document", type = "document", platform = "xuexitong", raw = ctx()),
                    TaskItem("bbs-1", name = "BBS", type = "bbs", platform = "xuexitong", raw = ctx()),
                )
                else -> emptyList()
            }
        }

        override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult {
            val action = options["action"] as? String ?: "none"
            val submitSuffix = when (action) {
                "chapterTest", "exam" -> ":${options["isSubmit"] == true && options["realSubmit"] == true}"
                "work" -> ":${options["isSubmit"] == true}"
                "bbs" -> ":${options["realSubmit"] == true}"
                else -> ""
            }
            calls.add("run:${task.id}:$action$submitSuffix")
            return when (action) {
                "pullChapterTest" -> RunTaskResult("xuexitong", task.id, "ok", raw = obj(
                    "meta" to ctx(),
                    "questions" to arr(question("q-chapter")),
                ))
                "xxtAI" -> RunTaskResult("xuexitong", task.id, "ok", raw = obj("answers" to arr("A")))
                "bbsPrepare" -> RunTaskResult("xuexitong", task.id, "ok", raw = ctx("prompt" to "Discuss"))
                "pullWorkList" -> RunTaskResult("xuexitong", task.id, "ok", raw = obj("works" to arr(obj("taskRefId" to "work-1", "name" to "Work"))))
                "enterWork" -> RunTaskResult("xuexitong", task.id, "ok", raw = ctx("workId" to "work-answer", "questionTotal" to 1))
                "workQuestion" -> RunTaskResult("xuexitong", task.id, "ok", raw = question("q-work"))
                "pullExamList" -> RunTaskResult("xuexitong", task.id, "ok", raw = obj("exams" to arr(obj("taskRefId" to "exam-1", "name" to "Exam"))))
                "enterExam" -> RunTaskResult("xuexitong", task.id, "ok", raw = ctx("examRelationId" to "exam-answer", "questionTotal" to 1))
                "examQuestion" -> RunTaskResult("xuexitong", task.id, "ok", raw = question("q-exam"))
                else -> RunTaskResult("xuexitong", task.id, "submitted")
            }
        }

        private fun question(qid: String) = obj(
            "qid" to qid,
            "typeCode" to "single",
            "content" to "Question $qid",
            "options" to obj("A" to "A", "B" to "B"),
            "submit" to obj(),
        )

        private fun ctx(vararg extra: Pair<String, Any>): JsonObject =
            obj(
                "classId" to "class-1",
                "courseId" to "course-1",
                "cpi" to "cpi-1",
                *extra,
        )
    }

    private class XuexitongFallbackRunner(private val fillBlank: Boolean = false) : CourseTaskRunner {
        val submittedAnswers = mutableListOf<String>()
        val submittedFinals = mutableListOf<Boolean>()
        private val course = CourseItem("course-1", name = "Course", platform = "xuexitong", raw = ctx())

        override suspend fun getCourses(session: SessionData): List<CourseItem> = listOf(course)

        override suspend fun getCourseDetail(session: SessionData, course: CourseItem): List<CourseItem> = emptyList()

        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> = emptyList()

        override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult {
            return when (options["action"] as? String ?: "none") {
                "pullWorkList" -> RunTaskResult("xuexitong", task.id, "ok", raw = obj("works" to arr(obj("taskRefId" to "work-1", "name" to "Work"))))
                "enterWork" -> RunTaskResult("xuexitong", task.id, "ok", raw = ctx("workId" to "work-answer", "questionTotal" to 1))
                "workQuestion" -> RunTaskResult("xuexitong", task.id, "ok", raw = if (fillBlank) {
                    obj(
                        "qid" to "q-work",
                        "typeCode" to "fill",
                        "content" to "Question",
                        "submit" to obj(),
                    )
                } else {
                    obj(
                        "qid" to "q-work",
                        "typeCode" to "single",
                        "content" to "Question",
                        "options" to obj("A" to "Option A", "B" to "Option B"),
                        "submit" to obj(),
                    )
                })
                "xxtAI" -> RunTaskResult("xuexitong", task.id, "ok", raw = JsonObject())
                "work" -> {
                    @Suppress("UNCHECKED_CAST")
                    val question = options["question"] as Map<String, Any>
                    @Suppress("UNCHECKED_CAST")
                    submittedAnswers.addAll(question["answers"] as List<String>)
                    submittedFinals.add(options["isSubmit"] == true)
                    RunTaskResult("xuexitong", task.id, "submitted")
                }
                else -> RunTaskResult("xuexitong", task.id, "ok")
            }
        }

        private fun ctx(vararg extra: Pair<String, Any>): JsonObject =
            obj(
                "classId" to "class-1",
                "courseId" to "course-1",
                "cpi" to "cpi-1",
                *extra,
            )
    }

    private class XuexitongBbsRunner : CourseTaskRunner {
        val submittedContent = mutableListOf<String>()
        val realSubmit = mutableListOf<Boolean>()
        private val course = CourseItem("course-1", name = "Course", platform = "xuexitong", raw = ctx())
        private val node = CourseItem("node-1", name = "Node", platform = "xuexitong", raw = ctx())

        override suspend fun getCourses(session: SessionData): List<CourseItem> = listOf(course)

        override suspend fun getCourseDetail(session: SessionData, course: CourseItem): List<CourseItem> =
            listOf(node)

        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> =
            listOf(TaskItem("bbs-1", name = "BBS", type = "bbs", platform = "xuexitong", raw = ctx()))

        override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult {
            return when (options["action"] as? String ?: "none") {
                "bbsPrepare" -> RunTaskResult("xuexitong", task.id, "prepared", raw = ctx(
                    "prompt" to "Discussion title\nDiscussion content",
                    "title" to "Discussion title",
                    "detail" to "Discussion content",
                    "jobId" to task.id,
                    "topicUUID" to "topic-1",
                    "topicClassId" to "class-1",
                ))
                "bbs" -> {
                    submittedContent.add(options["content"] as String)
                    realSubmit.add(options["realSubmit"] == true)
                    RunTaskResult("xuexitong", task.id, "submitted")
                }
                else -> RunTaskResult("xuexitong", task.id, "ok")
            }
        }

        private fun ctx(vararg extra: Pair<String, Any>): JsonObject =
            obj(
                "classId" to "class-1",
                "courseId" to "course-1",
                "cpi" to "cpi-1",
                "knowledgeId" to 111,
                *extra,
            )
    }

    private class HaiqikejiAnswerRunner(private val scope: String) : CourseTaskRunner {
        val actions = mutableListOf<String>()
        val submittedAnswers = mutableListOf<Map<String, Any>>()
        val realSubmitOptions = mutableListOf<Boolean>()
        private val course = CourseItem("course-1", name = "Course", platform = "haiqikeji", raw = ctx())

        override suspend fun getCourses(session: SessionData): List<CourseItem> = listOf(course)

        override suspend fun getTasks(session: SessionData, course: CourseItem): List<TaskItem> =
            listOf(TaskItem("node-$scope", name = "Node", type = scope, platform = "haiqikeji", raw = ctx("nodeId" to "node-$scope")))

        override suspend fun runTask(session: SessionData, task: TaskItem, options: Map<String, Any>): RunTaskResult {
            val action = options["action"] as? String ?: "none"
            actions.add(action)
            return when (action) {
                "pullWork" -> RunTaskResult(
                    "haiqikeji",
                    task.id,
                    "done",
                    raw = obj("works" to arr(quiz("work"))),
                )
                "pullExam" -> RunTaskResult(
                    "haiqikeji",
                    task.id,
                    "done",
                    raw = obj("exams" to arr(quiz("exam"))),
                )
                "workQuestions", "examQuestions" -> RunTaskResult(
                    "haiqikeji",
                    task.id,
                    "questions",
                    raw = obj("questions" to arr(question())),
                )
                "work", "exam" -> {
                    @Suppress("UNCHECKED_CAST")
                    val answers = options["answers"] as List<Map<String, Any>>
                    submittedAnswers.addAll(answers)
                    if (action == "exam") realSubmitOptions.add(options["realSubmit"] == true)
                    RunTaskResult("haiqikeji", task.id, if (action == "exam" && options["realSubmit"] != true) "dry_run" else "submitted")
                }
                else -> RunTaskResult("haiqikeji", task.id, "ok")
            }
        }

        private fun quiz(scope: String): JsonObject =
            obj(
                "${scope}Id" to "$scope-1",
                "title" to "$scope title",
                "courseId" to "course-1",
                "nodeId" to "node-$scope",
            )

        private fun question(): JsonObject =
            obj(
                "topicId" to "topic-1",
                "recordId" to "record-1",
                "wrId" to "wr-1",
                "waId" to "wa-1",
                "type" to 1,
                "content" to "Pick one",
                "options" to arr("Option A", "Option B"),
                "optionIdx" to arr("A", "B"),
            )

        private fun ctx(vararg extra: Pair<String, Any>): JsonObject =
            obj("courseId" to "course-1", *extra)
    }

    private class FakeAnswerProvider(private val answers: List<String>) : AnswerProvider {
        var answerCalls = 0
        val requests = mutableListOf<AnswerRequest>()
        override suspend fun answers(request: AnswerRequest): List<String> {
            answerCalls++
            requests.add(request)
            return answers
        }
    }

    private companion object {
        fun answerPlan(mode: AnswerMode): RunPlan = RunPlan(
            planId = "answer-plan-$mode",
            platform = "xuexitong",
            account = "stu",
            answerPolicy = AnswerPolicy(
                enabled = true,
                answerMode = mode,
                runWork = true,
                runExam = false,
                runChapterTest = false,
                submitWorkFinal = false,
            ),
        )

        fun haiqikejiAnswerPlan(
            scope: String,
            submitFinal: Boolean,
            realSubmitExam: Boolean = false,
        ): RunPlan = RunPlan(
            planId = "haiqikeji-$scope-answer-plan",
            platform = "haiqikeji",
            account = "stu",
            answerPolicy = AnswerPolicy(
                enabled = true,
                answerMode = AnswerMode.EXTERNAL_QUESTION_BANK,
                runWork = scope == "work",
                runExam = scope == "exam",
                runChapterTest = false,
                submitWorkFinal = submitFinal,
                submitExamFinal = submitFinal,
                realSubmitExam = realSubmitExam,
            ),
        )

        fun bbsPlan(): RunPlan = RunPlan(
            planId = "bbs-plan",
            platform = "xuexitong",
            account = "stu",
            answerPolicy = AnswerPolicy(
                enabled = true,
                answerMode = AnswerMode.HOST_AI,
                runWork = false,
                runExam = false,
                runChapterTest = false,
            ),
        )

        fun obj(vararg pairs: Pair<String, Any>): JsonObject = JsonObject().apply {
            for ((key, value) in pairs) {
                when (value) {
                    is String -> addProperty(key, value)
                    is Number -> addProperty(key, value)
                    is Boolean -> addProperty(key, value)
                    is JsonObject -> add(key, value)
                    is JsonArray -> add(key, value)
                    else -> addProperty(key, value.toString())
                }
            }
        }

        fun arr(vararg values: JsonObject): JsonArray = JsonArray().apply { values.forEach(::add) }
        fun arr(vararg values: String): JsonArray = JsonArray().apply { values.forEach(::add) }
    }
}
