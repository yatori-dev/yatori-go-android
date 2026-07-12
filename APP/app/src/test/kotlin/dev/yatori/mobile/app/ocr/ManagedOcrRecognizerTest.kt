package dev.yatori.mobile.app.ocr

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

class ManagedOcrRecognizerTest {
    @Test
    fun `production OCR idle timeout is thirty seconds`() {
        assertEquals(30_000L, OCR_IDLE_TIMEOUT_MS)
    }

    @Test
    fun `recognition creates only the model required by the platform`() = runTest {
        var commonCreates = 0
        var calcCreates = 0
        val recognizer = ManagedOcrRecognizer(
            scope = backgroundScope,
            workerDispatcher = StandardTestDispatcher(testScheduler),
            idleTimeoutMillis = 60_000L,
            commonFactory = {
                commonCreates++
                FakeRecognizer("common")
            },
            calcFactory = {
                calcCreates++
                FakeRecognizer("calc")
            },
        )

        assertEquals("common", recognizer.recognizeCaptchaBase64("yinghua", "image", 18))
        assertEquals(1, commonCreates)
        assertEquals(0, calcCreates)

        assertEquals("calc", recognizer.recognizeCaptchaBase64("qingshuxuetang", "image", null))
        assertEquals(1, commonCreates)
        assertEquals(1, calcCreates)
    }

    private class FakeRecognizer(private val result: String) : CloseableCaptchaRecognizer {
        override fun recognizeCaptchaBase64(platformId: String, imageBase64: String, outputCols: Int?): String = result
        override fun close() = Unit
    }
}
