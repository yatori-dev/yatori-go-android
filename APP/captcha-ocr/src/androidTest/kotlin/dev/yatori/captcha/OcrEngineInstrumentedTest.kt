package dev.yatori.captcha

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the real ONNX model + charcode table load from assets and the engine runs end to
 * end on a synthetic captcha bitmap (no real account / no network). Recognition accuracy on
 * real platform captchas remains pending real-account validation.
 */
@RunWith(AndroidJUnit4::class)
class OcrEngineInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun engine(): OcrEngine {
        val model = ctx.assets.open("common_old.onnx").use { it.readBytes() }
        val chars = ctx.assets.open("charcode.json").use { it.bufferedReader().readText() }
        return OcrEngine(model, OcrDecoder(chars))
    }

    private fun syntheticCaptcha(text: String, width: Int = 140, height: Int = 64): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.BLACK; textSize = 40f; isAntiAlias = true }
        c.drawText(text, 10f, 46f, p)
        return bmp
    }

    @Test
    fun engineLoadsAndRunsWithoutCrashing() {
        engine().use { e ->
            val bmp = syntheticCaptcha("a1b2")
            val result = e.recognize(bmp)
            assertNotNull(result) // model runs; exact text not asserted (synthetic input)
            bmp.recycle()
        }
    }

    @Test
    fun recognizeSemiRunsForGivenCols() {
        engine().use { e ->
            val bmp = syntheticCaptcha("1234", width = 100)
            val result = e.recognizeSemi(bmp, outputCols = 18)
            assertNotNull(result)
            bmp.recycle()
        }
    }

    @Test
    fun charCodeTableIsLarge() {
        val chars = ctx.assets.open("charcode.json").use { it.bufferedReader().readText() }
        assertTrue("charcode table should be the full ddddocr set", OcrDecoder(chars).tableSize > 1000)
    }
}
