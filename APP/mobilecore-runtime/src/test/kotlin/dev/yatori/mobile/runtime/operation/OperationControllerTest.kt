package dev.yatori.mobile.runtime.operation

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class OperationControllerTest {

    private fun controller() = OperationController(now = { 0L })

    @Test
    fun `start adds a running operation`() {
        val c = controller()
        val id = c.start(OperationType.LOGIN, "yinghua", "user@example.com")
        val op = c.operations.value.single()
        assertEquals(1, c.operations.value.size)
        assertEquals(OperationStatus.RUNNING, op.status)
        assertEquals(id, op.id)
        assertEquals("user@example.com", op.account)
        assertEquals("u***r@example.com", op.accountMasked)
    }

    @Test
    fun `updateProgress sets completed and total`() {
        val c = controller()
        val id = c.start(OperationType.BATCH_LEARN, "cqie", "account-a")
        c.updateProgress(id, completed = 3, total = 10)
        val op = c.operations.value.single()
        assertEquals(3, op.completed)
        assertEquals(10, op.total)
        assertEquals(0.3f, op.progressFraction)
    }

    @Test
    fun `cancel flips to CANCELLING and fires hook`() {
        val c = controller()
        var hookFired = false
        val id = c.start(OperationType.BATCH_LEARN, "cqie", "account-a", cancelHook = { hookFired = true })
        c.cancel(id)
        assertTrue(hookFired)
        assertTrue(c.isCancelling(id))
        assertEquals(OperationStatus.CANCELLING, c.operations.value.single().status)
    }

    @Test
    fun `markDone removes hook and sets DONE`() {
        val c = controller()
        val id = c.start(OperationType.RUN_TASK, "p", "account-a")
        c.markDone(id, detail = "ok")
        assertEquals(OperationStatus.DONE, c.operations.value.single().status)
        // cancel after done is a no-op
        c.cancel(id)
        assertEquals(OperationStatus.DONE, c.operations.value.single().status)
    }

    @Test
    fun `clearFinished drops done and failed`() {
        val c = controller()
        val a = c.start(OperationType.RUN_TASK, "p", "account-a")
        val b = c.start(OperationType.RUN_TASK, "p", "account-b")
        c.markDone(a)
        c.markFailed(b)
        c.clearFinished()
        assertTrue(c.operations.value.isEmpty())
    }

    @Test
    fun `clearFinished also drops question history for finished operations`() {
        val c = controller()
        val a = c.start(OperationType.BATCH_LEARN, "xuexitong", "student")
        c.upsertQuestionHistory(
            QuestionHistoryEntry(
                id = "q1",
                operationId = a,
                platform = "xuexitong",
                accountMasked = "s",
                scope = "work",
                taskId = "work-1",
                label = "work question 1",
                status = QuestionHistoryStatus.ANSWERED,
            ),
        )

        c.markDone(a)
        c.clearFinished()

        assertTrue(c.questionHistory.value.isEmpty())
    }

    @Test
    fun `maskAccount hides the middle`() {
        assertEquals("u***r@example.com", OperationController.maskAccount("user@example.com"))
        assertEquals("a*", OperationController.maskAccount("ab"))
        assertEquals("", OperationController.maskAccount(""))
    }

    @Test
    fun `active excludes finished`() {
        val c = controller()
        val a = c.start(OperationType.RUN_TASK, "p", "account-a")
        c.start(OperationType.RUN_TASK, "p", "account-b")
        c.markDone(a)
        assertEquals(1, c.active.size)
        assertFalse(c.active.any { it.id == a })
    }
}
