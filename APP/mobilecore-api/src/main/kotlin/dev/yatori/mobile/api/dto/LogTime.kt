package dev.yatori.mobile.api.dto

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FULL_LOG_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

private val SHORT_LOG_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA)

/** Formats a Go-core RFC3339 log timestamp in the device/system time zone. */
fun LogEntry.localFullTime(zoneId: ZoneId = ZoneId.systemDefault()): String =
    formatLogTime(time, FULL_LOG_TIME_FORMATTER, zoneId)

/** Formats a Go-core RFC3339 log timestamp as MM-dd HH:mm in the device/system time zone. */
fun LogEntry.localShortTime(zoneId: ZoneId = ZoneId.systemDefault()): String =
    formatLogTime(time, SHORT_LOG_TIME_FORMATTER, zoneId)

/** Human-readable log line for txt export. JSONL export keeps the original raw timestamp. */
fun LogEntry.localReadableLine(zoneId: ZoneId = ZoneId.systemDefault()): String {
    val lvl = (level ?: "").uppercase(Locale.ROOT)
    val src = source ?: ""
    val plat = platform ?: ""
    val msg = message ?: ""
    return "${localFullTime(zoneId)} [$lvl] $src${if (plat.isNotBlank()) "($plat)" else ""}: $msg"
}

private fun formatLogTime(raw: String, formatter: DateTimeFormatter, zoneId: ZoneId): String {
    val value = raw.trim()
    if (value.isEmpty()) return raw
    val zoned = parseLogTime(value, zoneId) ?: return raw
    return zoned.format(formatter)
}

private fun parseLogTime(value: String, zoneId: ZoneId): ZonedDateTime? =
    runCatching { Instant.parse(value).atZone(zoneId) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).atZoneSameInstant(zoneId) }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.ISO_ZONED_DATE_TIME).withZoneSameInstant(zoneId) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(zoneId) }.getOrNull()
