package dev.yatori.mobile.runtime.log

import dev.yatori.mobile.api.dto.LogEntry
import dev.yatori.mobile.api.dto.LogResult
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class UnifiedLogPipelineTest {
    @Test
    fun `android and delayed core logs are sorted before one flush`() = runTest {
        val dir = Files.createTempDirectory("unified-log-pipeline").toFile()
        try {
            val store = EncryptedLogStore(
                dir = dir,
                crypto = AesGcmLogCrypto(ByteArray(32) { it.toByte() }),
                sessionStartMillis = 1_000L,
            )
            var coreDrains = 0
            val coreLogs = listOf(
                entry(1, 9_000_000L, "core-9"),
                entry(2, 10_000_000L, "core-10"),
            )
            val pipeline = UnifiedLogPipeline(
                scope = backgroundScope,
                store = store,
                fetchCoreLogs = {
                    coreDrains++
                    LogResult("2", "1", false, coreLogs)
                },
                clearCoreLogs = {},
                batchWindowMillis = 300L,
                fallbackIntervalMillis = 60_000L,
            )

            pipeline.submitAndroid(entry(-1, 12_000_000L, "android-12"))
            pipeline.notifyCoreLogsAvailable()
            pipeline.flush()

            assertEquals(1, coreDrains)
            assertEquals(
                listOf("core-9", "core-10", "android-12"),
                store.readCurrentSession().map { it.message },
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `clear drops pending micro batch and clears both stores`() = runTest {
        val dir = Files.createTempDirectory("unified-log-clear").toFile()
        try {
            val store = EncryptedLogStore(
                dir = dir,
                crypto = AesGcmLogCrypto(ByteArray(32) { it.toByte() }),
                sessionStartMillis = 2_000L,
            )
            var coreClears = 0
            val pipeline = UnifiedLogPipeline(
                scope = backgroundScope,
                store = store,
                fetchCoreLogs = { LogResult("", "", false, emptyList()) },
                clearCoreLogs = { coreClears++ },
            )

            pipeline.submitAndroid(entry(-2, 20_000_000L, "pending"))
            pipeline.clear()
            pipeline.flush()

            assertEquals(1, coreClears)
            assertEquals(emptyList(), store.readCurrentSession())
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun entry(id: Long, micros: Long, message: String) = LogEntry(
        id = id,
        time = "2026-07-13T00:00:00Z",
        timestampMicros = micros,
        level = "info",
        source = if (id < 0) "android-runtime" else "mobilecore",
        message = message,
    )
}
