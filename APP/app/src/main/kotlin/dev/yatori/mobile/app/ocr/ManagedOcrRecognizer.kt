package dev.yatori.mobile.app.ocr

import dev.yatori.captcha.OcrEngine
import java.io.Closeable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext

internal const val OCR_IDLE_TIMEOUT_MS = 30_000L

internal interface CloseableCaptchaRecognizer : Closeable {
    fun recognizeCaptchaBase64(platformId: String, imageBase64: String, outputCols: Int?): String?
}

internal class OcrEngineRecognizer(private val engine: OcrEngine) : CloseableCaptchaRecognizer {
    override fun recognizeCaptchaBase64(platformId: String, imageBase64: String, outputCols: Int?): String? =
        engine.recognizeCaptchaBase64(platformId, imageBase64, outputCols)

    override fun close() = engine.close()
}

/** Routes each platform to only the OCR model it needs and releases idle model sessions. */
internal class ManagedOcrRecognizer(
    scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher,
    idleTimeoutMillis: Long,
    commonFactory: () -> CloseableCaptchaRecognizer,
    calcFactory: () -> CloseableCaptchaRecognizer,
) {
    private val common = IdleCloseableResource(scope, idleTimeoutMillis, commonFactory)
    private val calc = IdleCloseableResource(scope, idleTimeoutMillis, calcFactory)

    suspend fun recognizeCaptchaBase64(
        platformId: String,
        imageBase64: String,
        outputCols: Int?,
    ): String? = withContext(workerDispatcher) {
        val resource = if (platformId == CALC_PLATFORM) calc else common
        resource.use { it.recognizeCaptchaBase64(platformId, imageBase64, outputCols) }
    }

    private companion object {
        const val CALC_PLATFORM = "qingshuxuetang"
    }
}
