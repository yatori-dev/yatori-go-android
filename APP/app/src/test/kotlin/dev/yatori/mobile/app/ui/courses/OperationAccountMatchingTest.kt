package dev.yatori.mobile.app.ui.courses

import dev.yatori.mobile.api.dto.SessionData
import dev.yatori.mobile.runtime.StoredSession
import dev.yatori.mobile.runtime.operation.Operation
import dev.yatori.mobile.runtime.operation.OperationController
import dev.yatori.mobile.runtime.operation.OperationStatus
import dev.yatori.mobile.runtime.operation.OperationType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OperationAccountMatchingTest {
    @Test
    fun `operation with real account only appears on matching account card`() {
        val sessions = listOf(
            session("xuexitong", "17711111115"),
            session("xuexitong", "17722222215"),
        )
        val op = Operation(
            id = "op-1",
            type = OperationType.BATCH_LEARN,
            platform = "xuexitong",
            account = "17722222215",
            accountMasked = OperationController.maskAccount("17722222215"),
            status = OperationStatus.FAILED,
            detail = "course sync failed",
        )

        assertNull(listOf(op).accountRunOp(sessions[0], sessions))
        assertEquals(op, listOf(op).accountRunOp(sessions[1], sessions))
    }

    @Test
    fun `legacy masked operation is ignored when multiple accounts share same mask`() {
        val sessions = listOf(
            session("xuexitong", "17711111115"),
            session("xuexitong", "17722222215"),
        )
        val legacy = Operation(
            id = "op-legacy",
            type = OperationType.BATCH_LEARN,
            platform = "xuexitong",
            account = "",
            accountMasked = OperationController.maskAccount("17722222215"),
            status = OperationStatus.FAILED,
            detail = "legacy failure",
        )

        assertNull(listOf(legacy).accountRunOp(sessions[0], sessions))
        assertNull(listOf(legacy).accountRunOp(sessions[1], sessions))
    }

    private fun session(platform: String, account: String) =
        StoredSession(platform, account, SessionData(platform = platform, account = account), updatedAt = 0L)
}
