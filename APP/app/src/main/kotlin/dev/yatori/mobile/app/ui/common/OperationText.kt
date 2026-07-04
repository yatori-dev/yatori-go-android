package dev.yatori.mobile.app.ui.common

import dev.yatori.mobile.runtime.operation.OperationStatus

internal fun operationSummaryText(
    status: OperationStatus,
    completed: Int,
    total: Int,
    detail: String,
): String = buildString {
    append(operationStatusText(status))
    if (total > 0) append(" ").append(completed).append('/').append(total)
    val cleanDetail = operationDetailText(detail)
    if (cleanDetail.isNotBlank()) append(" · ").append(cleanDetail)
}

internal fun operationStatusText(status: OperationStatus): String = when (status) {
    OperationStatus.RUNNING -> "运行中"
    OperationStatus.CANCELLING -> "正在取消"
    OperationStatus.DONE -> "已完成"
    OperationStatus.FAILED -> "失败"
}

private fun operationDetailText(detail: String): String {
    val text = detail.trim()
    if (text.isBlank()) return ""
    Regex("""^tasks=(\d+)$""").matchEntire(text)?.let {
        return "共 ${it.groupValues[1]} 个任务"
    }
    Regex("""^done submitted=(\d+)$""").matchEntire(text)?.let {
        return "已提交 ${it.groupValues[1]} 个任务"
    }
    Regex("""^cancelled submitted=(\d+)$""").matchEntire(text)?.let {
        return "已取消，已提交 ${it.groupValues[1]} 个任务"
    }
    Regex("""^(\d+)/(\d+)$""").matchEntire(text)?.let {
        return "已完成 ${it.groupValues[1]}/${it.groupValues[2]}"
    }
    return when (text) {
        "sync courses" -> "正在同步课程"
        "dry_run" -> "未提交"
        "submitted" -> "已提交"
        "done" -> "已完成"
        "cancelled" -> "已取消"
        "failed" -> "失败"
        else -> text
    }
}
