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

/** Event timestamp used to merge Go and Android logs; old persisted logs fall back to [time]. */
fun LogEntry.eventTimeMicros(zoneId: ZoneId = ZoneId.systemDefault()): Long? =
    timestampMicros ?: parseLogInstant(time.trim(), zoneId)?.let { instant ->
        instant.epochSecond * MICROS_PER_SECOND + instant.nano / NANOS_PER_MICRO
    }

private fun formatLogTime(raw: String, formatter: DateTimeFormatter, zoneId: ZoneId): String {
    val value = raw.trim()
    if (value.isEmpty()) return raw
    val zoned = parseLogInstant(value, zoneId)?.atZone(zoneId) ?: return raw
    return zoned.format(formatter)
}

private fun parseLogInstant(value: String, fallbackZone: ZoneId): Instant? =
    runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }.getOrNull()
        ?: runCatching { ZonedDateTime.parse(value, DateTimeFormatter.ISO_ZONED_DATE_TIME).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(fallbackZone).toInstant() }.getOrNull()

private const val MICROS_PER_SECOND = 1_000_000L
private const val NANOS_PER_MICRO = 1_000
