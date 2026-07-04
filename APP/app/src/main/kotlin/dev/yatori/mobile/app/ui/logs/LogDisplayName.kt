package dev.yatori.mobile.app.ui.logs

import com.google.gson.JsonObject
import dev.yatori.mobile.api.dto.LogEntry
import dev.yatori.mobile.api.dto.MobileConfig
import dev.yatori.mobile.app.platform.platformDisplayName
import dev.yatori.mobile.app.ui.courses.accountSnippetForRemark
import dev.yatori.mobile.runtime.StoredSession

internal const val SYSTEM_GO_CORE_LABEL = "系统/GoCore"

internal fun logDisplayName(
    entry: LogEntry,
    sessions: List<StoredSession>,
    config: MobileConfig?,
): String {
    val platform = entry.platform.orEmpty().trim()
    val source = entry.source.orEmpty()
    if (platform.isBlank() || source.equals("mobilecore", ignoreCase = true) && platform.isBlank()) {
        return SYSTEM_GO_CORE_LABEL
    }

    val platformSessions = sessions.filter { it.platform.equals(platform, ignoreCase = true) }
    val matched = findAccountFromEntry(entry, platformSessions)
        ?: findAccountInLog(entry, platformSessions)
        ?: platformSessions.singleOrNull()
    if (matched != null) {
        val remark = config?.users
            ?.firstOrNull { it.matchesAccount(matched.platform, matched.account) }
            ?.str("remarkName")
            .orEmpty()
        val platformName = platformDisplayName(matched.platform, fallback = matched.platform)
        return if (remark.isNotBlank()) {
            val snippet = accountSnippetForRemark(matched.account)
            "$remark（$snippet）"
        } else {
            "$platformName（${matched.account}）"
        }
    }

    return platformDisplayName(platform, fallback = SYSTEM_GO_CORE_LABEL)
}

private fun findAccountFromEntry(entry: LogEntry, sessions: List<StoredSession>): StoredSession? {
    val account = entry.account.orEmpty().trim()
    if (account.isBlank() || sessions.isEmpty()) return null
    return sessions.firstOrNull { it.account == account }
}

private fun findAccountInLog(entry: LogEntry, sessions: List<StoredSession>): StoredSession? {
    if (sessions.isEmpty()) return null
    val haystack = "${entry.source.orEmpty()}\n${entry.message.orEmpty()}".lowercase()
    return sessions.firstOrNull { it.account.isNotBlank() && haystack.contains(it.account.lowercase()) }
}

private fun JsonObject.matchesAccount(platform: String, account: String): Boolean =
    str("account").equals(account, ignoreCase = false) &&
        str("accountType").equals(platform, ignoreCase = true)

private fun JsonObject.str(key: String): String =
    runCatching { get(key)?.asString.orEmpty() }.getOrDefault("")
