package dev.yatori.mobile.runtime.operation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class OperationType { LOGIN, RUN_TASK, BATCH_LEARN }

enum class OperationStatus { RUNNING, CANCELLING, DONE, FAILED }

enum class QuestionHistoryStatus { PENDING, ANSWERED, WAITING_EDIT, MISSING, SAVED, SUBMITTED, SKIPPED, FAILED }

/** Immutable snapshot of one long-running operation, observed by the UI via [StateFlow]. */
data class Operation(
    val id: String,
    val type: OperationType,
    val platform: String,
    val account: String = "",
    val accountMasked: String = "",
    val status: OperationStatus = OperationStatus.RUNNING,
    val completed: Int = 0,
    val total: Int = 0,
    val detail: String = "",
    val updatedAt: Long = 0L,
) {
    val progressFraction: Float get() = if (total > 0) completed.toFloat() / total else 0f
}

data class QuestionHistoryEntry(
    val id: String,
    val operationId: String,
    val platform: String,
    val accountMasked: String,
    val scope: String,
    val taskId: String,
    val label: String,
    val questionIndex: Int = 0,
    val questionTotal: Int = 0,
    val questionId: String = "",
    val questionType: String = "",
    val contentPreview: String = "",
    val answerPreview: String = "",
    val answerSource: String = "",
    val finalSubmit: Boolean = false,
    val realSubmit: Boolean = false,
    val status: QuestionHistoryStatus = QuestionHistoryStatus.PENDING,
    val message: String = "",
    val updatedAt: Long = 0L,
)

/**
 * Unified lifecycle + cancellation control for all long operations (login, run-task,
 * batch-learn). Pure state holder — exposes a [StateFlow] the UI observes and never holds
 * execution logic itself.
 *
 * Cancellation semantics differ by operation type and are enforced by the CALLER that owns
 * the work (see brief §7.3):
 *  - LOGIN: caller cancels the OCR/login coroutine job AND, if a Go-core Challenge taskId is
 *    live, calls cancelLogin(taskId).
 *  - RUN_TASK / BATCH_LEARN: caller stops the Android scheduling loop; an in-flight runTask is
 *    marked CANCELLING and allowed to return — cancelLogin is NEVER called for these.
 *
 * [cancel] flips status to CANCELLING and invokes the registered [CancelHook]; the caller is
 * responsible for the actual job cancellation and then calling [markDone]/[markFailed].
 */
class OperationController(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val maxQuestionHistory: Int = 500,
) {
    fun interface CancelHook { fun onCancel(operation: Operation) }

    private val _operations = MutableStateFlow<List<Operation>>(emptyList())
    val operations: StateFlow<List<Operation>> = _operations

    private val _questionHistory = MutableStateFlow<List<QuestionHistoryEntry>>(emptyList())
    val questionHistory: StateFlow<List<QuestionHistoryEntry>> = _questionHistory

    private val cancelHooks = HashMap<String, CancelHook>()
    private var seq = 0L

    /** Active (RUNNING or CANCELLING) operations. */
    val active: List<Operation>
        get() = _operations.value.filter { it.status == OperationStatus.RUNNING || it.status == OperationStatus.CANCELLING }

    @Synchronized
    fun start(
        type: OperationType,
        platform: String,
        account: String,
        accountMasked: String = maskAccount(account),
        total: Int = 0,
        detail: String = "",
        cancelHook: CancelHook? = null,
    ): String {
        val id = "op-${++seq}"
        cancelHook?.let { cancelHooks[id] = it }
        _operations.update { it + Operation(id, type, platform, account, accountMasked, OperationStatus.RUNNING, 0, total, detail, now()) }
        return id
    }

    fun updateProgress(id: String, completed: Int, total: Int? = null, detail: String? = null) =
        mutate(id) { it.copy(completed = completed, total = total ?: it.total, detail = detail ?: it.detail, updatedAt = now()) }

    fun markDone(id: String, detail: String? = null) {
        mutate(id) { it.copy(status = OperationStatus.DONE, detail = detail ?: it.detail, updatedAt = now()) }
        cancelHooks.remove(id)
    }

    fun markFailed(id: String, detail: String? = null) {
        mutate(id) { it.copy(status = OperationStatus.FAILED, detail = detail ?: it.detail, updatedAt = now()) }
        cancelHooks.remove(id)
    }

    fun upsertQuestionHistory(entry: QuestionHistoryEntry) {
        val withTime = entry.copy(updatedAt = if (entry.updatedAt == 0L) now() else entry.updatedAt)
        _questionHistory.update { list ->
            val next = if (list.any { it.id == withTime.id }) {
                list.map { if (it.id == withTime.id) withTime else it }
            } else {
                list + withTime
            }
            if (next.size <= maxQuestionHistory) next else next.takeLast(maxQuestionHistory)
        }
    }

    fun clearQuestionHistory(operationId: String) {
        _questionHistory.update { list -> list.filterNot { it.operationId == operationId } }
    }

    /** Request cancellation: flip to CANCELLING and fire the registered hook. */
    fun cancel(id: String) {
        val op = _operations.value.find { it.id == id } ?: return
        if (op.status != OperationStatus.RUNNING) return
        mutate(id) { it.copy(status = OperationStatus.CANCELLING, updatedAt = now()) }
        cancelHooks[id]?.onCancel(op)
    }

    /** True while a caller-driven cancel is in progress for [id] (scheduling loops poll this). */
    fun isCancelling(id: String): Boolean =
        _operations.value.find { it.id == id }?.status == OperationStatus.CANCELLING

    /** Drops DONE/FAILED operations from the visible list. */
    fun clearFinished() {
        val finishedIds = _operations.value
            .filter { it.status == OperationStatus.DONE || it.status == OperationStatus.FAILED }
            .map { it.id }
            .toSet()
        _operations.update { list -> list.filter { it.status == OperationStatus.RUNNING || it.status == OperationStatus.CANCELLING } }
        if (finishedIds.isNotEmpty()) {
            _questionHistory.update { list -> list.filterNot { it.operationId in finishedIds } }
        }
    }

    private fun mutate(id: String, transform: (Operation) -> Operation) {
        _operations.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    companion object {
        /** Mask an account string for display/logging: keep first + last char of the local part. */
        fun maskAccount(account: String): String {
            if (account.isBlank()) return ""
            val at = account.indexOf('@')
            val local = if (at > 0) account.substring(0, at) else account
            val domain = if (at > 0) account.substring(at) else ""
            val masked = when {
                local.length <= 2 -> "${local.first()}*"
                else -> "${local.first()}***${local.last()}"
            }
            return masked + domain
        }
    }
}
