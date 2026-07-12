package dev.yatori.captcha

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * Android-side captcha OCR engine. ONNX Runtime inference only — no UI, no Context held.
 *
 * Two models are supported:
 *  - common_old.onnx  — CTC text recognition (yinghua / cqie / xuexitong captchas)
 *  - calc_det.onnx    — YOLO-style arithmetic character detection (qingshuxuetang captcha)
 *
 * Pass calcDetModelBytes to enable the arithmetic path; without it qingshuxuetang falls
 * back to the OCR path (lower accuracy for arithmetic expressions).
 */
class OcrEngine(
    modelBytes: ByteArray?,
    private val decoder: OcrDecoder,
    calcDetModelBytes: ByteArray? = null,
) : Closeable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession? = modelBytes?.let {
        env.createSession(it, OrtSession.SessionOptions())
    }
    private val calcDetSession: OrtSession? = calcDetModelBytes?.let {
        runCatching { env.createSession(it, OrtSession.SessionOptions()) }.getOrNull()
    }

    fun recognize(bitmap: Bitmap): String {
        val (data, inShape) = preprocess(bitmap)
        return runCatching { runInference(data, inShape) }.getOrDefault("")
    }

    fun recognizeSemi(bitmap: Bitmap, outputCols: Int): String {
        val (data, inShape) = preprocess(bitmap)
        return runCatching { runInference(data, inShape) }.getOrDefault("")
    }

    fun recognizeBase64(imageBase64: String, outputCols: Int? = null): String {
        val bmp = decodeBase64(imageBase64) ?: return ""
        return try {
            if (outputCols != null) recognizeSemi(bmp, outputCols) else recognize(bmp)
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Recognizes and normalizes a platform captcha. Returns null until OCR has produced
     * a code that matches the platform format, so callers do not submit partial text.
     *
     * For qingshuxuetang: uses calc_det.onnx arithmetic detection when available,
     * mirroring console AutoDetectionForCalc + AutoCalc flow.
     */
    fun recognizeCaptchaBase64(platformId: String, imageBase64: String, outputCols: Int? = null): String? {
        if (platformId == "qingshuxuetang" && calcDetSession != null) {
            val bmp = decodeBase64(imageBase64) ?: return null
            return try {
                val result = recognizeArithmetic(bmp)
                result?.toString()
            } finally {
                bmp.recycle()
            }
        }
        val raw = recognizeBase64(imageBase64, outputCols)
        return normalizeCaptchaText(platformId, raw)
    }

    fun normalizeCaptchaText(platformId: String, raw: String): String? {
        return decoder.normalizeCaptchaText(platformId, raw)
    }

    /**
     * Arithmetic captcha recognition via calc_det.onnx (YOLO detection model).
     * Mirrors console: AutoDetectionForCalc(image, 7) → sort by X → AutoCalc.
     */
    private fun recognizeArithmetic(bitmap: Bitmap): Int? {
        val session = calcDetSession ?: return null
        val inputSize = 640
        val inputData = letterboxToCHWFloat32(bitmap, inputSize)
        val inShape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())

        val result = OnnxTensor.createTensor(env, FloatBuffer.wrap(inputData), inShape).use { input ->
            session.run(mapOf("images" to input)).use { output ->
                val raw = output[0].value
                when (raw) {
                    is Array<*> -> parseDetections(raw, bitmap.width, bitmap.height)
                    is FloatArray -> parseDetectionsFlat(raw, bitmap.width, bitmap.height)
                    else -> null
                }
            }
        } ?: return null

        if (result.isEmpty()) return null
        val sorted = result.sortedBy { it.x1 }
        return calcArithmetic(sorted)
    }

    /** Letterbox: resize to [size×size], pad with gray (114/255), CHW RGB float32. */
    private fun letterboxToCHWFloat32(bitmap: Bitmap, size: Int): FloatArray {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val scale = minOf(size.toFloat() / srcW, size.toFloat() / srcH)
        val newW = (srcW * scale).toInt().coerceAtLeast(1)
        val newH = (srcH * scale).toInt().coerceAtLeast(1)
        val padX = (size - newW) / 2
        val padY = (size - newH) / 2

        val srcPixels = IntArray(srcW * srcH)
        bitmap.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        val result = FloatArray(3 * size * size) { 114f / 255f }
        val rOffset = 0
        val gOffset = size * size
        val bOffset = 2 * size * size

        for (dy in 0 until newH) {
            for (dx in 0 until newW) {
                val sx = (dx + 0.5f) * srcW / newW - 0.5f
                val sy = (dy + 0.5f) * srcH / newH - 0.5f
                val x0 = sx.toInt().coerceIn(0, srcW - 1)
                val y0 = sy.toInt().coerceIn(0, srcH - 1)
                val x1 = (x0 + 1).coerceIn(0, srcW - 1)
                val y1 = (y0 + 1).coerceIn(0, srcH - 1)
                val ddx = (sx - x0).coerceIn(0f, 1f)
                val ddy = (sy - y0).coerceIn(0f, 1f)

                val p00 = srcPixels[y0 * srcW + x0]
                val p10 = srcPixels[y0 * srcW + x1]
                val p01 = srcPixels[y1 * srcW + x0]
                val p11 = srcPixels[y1 * srcW + x1]
                val w00 = (1 - ddx) * (1 - ddy)
                val w10 = ddx * (1 - ddy)
                val w01 = (1 - ddx) * ddy
                val w11 = ddx * ddy

                val outIdx = (padY + dy) * size + (padX + dx)
                result[rOffset + outIdx] = (Color.red(p00) * w00 + Color.red(p10) * w10 +
                        Color.red(p01) * w01 + Color.red(p11) * w11) / 255f
                result[gOffset + outIdx] = (Color.green(p00) * w00 + Color.green(p10) * w10 +
                        Color.green(p01) * w01 + Color.green(p11) * w11) / 255f
                result[bOffset + outIdx] = (Color.blue(p00) * w00 + Color.blue(p10) * w10 +
                        Color.blue(p01) * w01 + Color.blue(p11) * w11) / 255f
            }
        }
        return result
    }

    private data class DetBox(val x1: Float, val y1: Float, val x2: Float, val y2: Float,
                              val score: Float, val cls: Int)

    @Suppress("UNCHECKED_CAST")
    private fun parseDetections(raw: Array<*>, imgW: Int, imgH: Int): List<DetBox>? {
        val scale = maxOf(imgW, imgH).toFloat() / 640f
        val list = mutableListOf<DetBox>()
        val batch = raw.firstOrNull() as? Array<*> ?: return parseDetectionsAsFloatMatrix(raw, imgW, imgH)
        for (row in batch) {
            val vals = when (row) {
                is FloatArray -> row
                is Array<*> -> (row as? Array<Float>)?.toFloatArray() ?: continue
                else -> continue
            }
            if (vals.size < 6) continue
            val x1 = vals[0] * scale; val y1 = vals[1] * scale
            val x2 = vals[2] * scale; val y2 = vals[3] * scale
            val score = vals[4]; val cls = vals[5].toInt()
            if (cls in CALC_CHARS.indices) list.add(DetBox(x1, y1, x2, y2, score, cls))
        }
        return list.sortedByDescending { it.score }.take(7)
    }

    private fun parseDetectionsAsFloatMatrix(raw: Array<*>, imgW: Int, imgH: Int): List<DetBox>? {
        val scale = maxOf(imgW, imgH).toFloat() / 640f
        val list = mutableListOf<DetBox>()
        val rows = raw.map { (it as? FloatArray) ?: return null }
        if (rows.size < 6) return null
        val numAttrs = rows[0].size
        for (i in 0 until numAttrs) {
            val x1 = rows[0][i] * scale; val y1 = rows[1][i] * scale
            val x2 = rows[2][i] * scale; val y2 = rows[3][i] * scale
            val score = rows[4][i]; val cls = rows[5][i].toInt()
            if (cls in CALC_CHARS.indices) list.add(DetBox(x1, y1, x2, y2, score, cls))
        }
        return list.sortedByDescending { it.score }.take(7)
    }

    private fun parseDetectionsFlat(raw: FloatArray, imgW: Int, imgH: Int): List<DetBox>? {
        val scale = maxOf(imgW, imgH).toFloat() / 640f
        val list = mutableListOf<DetBox>()
        var i = 0
        while (i + 6 <= raw.size) {
            val x1 = raw[i] * scale; val y1 = raw[i + 1] * scale
            val x2 = raw[i + 2] * scale; val y2 = raw[i + 3] * scale
            val score = raw[i + 4]; val cls = raw[i + 5].toInt()
            if (cls in CALC_CHARS.indices) list.add(DetBox(x1, y1, x2, y2, score, cls))
            i += 6
        }
        return list.sortedByDescending { it.score }.take(7)
    }

    private fun calcArithmetic(dets: List<DetBox>): Int? {
        if (dets.isEmpty()) return null
        val expr = dets.joinToString("") { CALC_CHARS[it.cls] }
        return when {
            '+' in expr -> expr.split('+').let { parts ->
                if (parts.size < 2) null else
                    (parts[0].toIntOrNull() ?: return null) +
                    (parts[1].split('=', '?').first().toIntOrNull() ?: return null)
            }
            '-' in expr -> expr.split('-').let { parts ->
                if (parts.size < 2) null else
                    (parts[0].toIntOrNull() ?: return null) -
                    (parts[1].split('=', '?').first().toIntOrNull() ?: return null)
            }
            'x' in expr -> expr.split('x').let { parts ->
                if (parts.size < 2) null else
                    (parts[0].toIntOrNull() ?: return null) *
                    (parts[1].split('=', '?').first().toIntOrNull() ?: return null)
            }
            '*' in expr -> expr.split('*').let { parts ->
                if (parts.size < 2) null else
                    (parts[0].toIntOrNull() ?: return null) *
                    (parts[1].split('=', '?').first().toIntOrNull() ?: return null)
            }
            '/' in expr -> expr.split('/').let { parts ->
                if (parts.size < 2) null else
                    (parts[0].toIntOrNull() ?: return null) /
                    (parts[1].split('=', '?').first().toIntOrNull()?.takeIf { it != 0 } ?: return null)
            }
            '÷' in expr -> expr.split('÷').let { parts ->
                if (parts.size < 2) null else
                    (parts[0].toIntOrNull() ?: return null) /
                    (parts[1].split('=', '?').first().toIntOrNull()?.takeIf { it != 0 } ?: return null)
            }
            else -> null
        }
    }

    private fun preprocess(bitmap: Bitmap): Pair<FloatArray, LongArray> {
        val h = 64
        val w = (64 * bitmap.width.toFloat() / bitmap.height).toInt().coerceAtLeast(1)
        val srcW = bitmap.width
        val srcH = bitmap.height
        val srcPixels = IntArray(srcW * srcH)
        bitmap.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)
        val arr = FloatArray(w * h)
        var idx = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sx = (x + 0.5f) * srcW / w - 0.5f
                val sy = (y + 0.5f) * srcH / h - 0.5f
                val x0 = sx.toInt().coerceIn(0, srcW - 1)
                val y0 = sy.toInt().coerceIn(0, srcH - 1)
                val x1 = (x0 + 1).coerceIn(0, srcW - 1)
                val y1 = (y0 + 1).coerceIn(0, srcH - 1)
                val dx = (sx - x0).coerceIn(0f, 1f)
                val dy = (sy - y0).coerceIn(0f, 1f)
                val p00 = srcPixels[y0 * srcW + x0]
                val p10 = srcPixels[y0 * srcW + x1]
                val p01 = srcPixels[y1 * srcW + x0]
                val p11 = srcPixels[y1 * srcW + x1]
                val wx0 = 1f - dx; val wx1 = dx; val wy0 = 1f - dy; val wy1 = dy
                val r = Color.red(p00) * wx0 * wy0 + Color.red(p10) * wx1 * wy0 +
                         Color.red(p01) * wx0 * wy1 + Color.red(p11) * wx1 * wy1
                val g = Color.green(p00) * wx0 * wy0 + Color.green(p10) * wx1 * wy0 +
                         Color.green(p01) * wx0 * wy1 + Color.green(p11) * wx1 * wy1
                val b = Color.blue(p00) * wx0 * wy0 + Color.blue(p10) * wx1 * wy0 +
                         Color.blue(p01) * wx0 * wy1 + Color.blue(p11) * wx1 * wy1
                arr[idx++] = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            }
        }
        return arr to longArrayOf(1, 1, 64, w.toLong())
    }

    private fun runInference(data: FloatArray, inShape: LongArray): String {
        val activeSession = session ?: return ""
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), inShape).use { input ->
            activeSession.run(mapOf(INPUT_NAME to input)).use { result ->
                val onnxValue = try {
                    result.get(OUTPUT_NAME).get()
                } catch (_: Exception) {
                    try { result.iterator().next().value }
                    catch (_: Exception) { return "" }
                }
                val raw: Any = try { onnxValue.value } catch (_: Exception) { return "" }
                    ?: return ""

                @Suppress("UNCHECKED_CAST")
                val indices: LongArray = when (raw) {
                    is LongArray -> raw
                    is IntArray  -> LongArray(raw.size) { raw[it].toLong() }
                    is Array<*>  -> {
                        val first = raw.firstOrNull()
                        when {
                            first is LongArray  -> first
                            first is IntArray   -> LongArray(first.size) { first[it].toLong() }
                            first is FloatArray -> ctcArgmax(raw as Array<FloatArray>)
                            first is Array<*>   -> {
                                val inner = first.firstOrNull()
                                when {
                                    inner is LongArray  -> inner
                                    inner is IntArray   -> LongArray(inner.size) { inner[it].toLong() }
                                    inner is FloatArray ->
                                        ctcArgmax(Array(raw.size) { t ->
                                            ((raw[t] as? Array<*>)?.firstOrNull() as? FloatArray)
                                                ?: FloatArray(0)
                                        })
                                    else -> LongArray(0)
                                }
                            }
                            else -> LongArray(0)
                        }
                    }
                    else -> LongArray(0)
                }
                return decoder.decode(indices)
            }
        }
    }

    private fun ctcCollapseRepeats(indices: LongArray): LongArray {
        if (indices.isEmpty()) return indices
        val out = ArrayList<Long>(indices.size)
        var prev = -1L
        for (v in indices) { if (v != prev) { out.add(v); prev = v } }
        return out.toLongArray()
    }

    @Suppress("UNCHECKED_CAST")
    private fun ctcArgmax(logits: Array<FloatArray>): LongArray =
        LongArray(logits.size) { t ->
            val row = logits[t]
            if (row.isEmpty()) 0L else row.indices.maxByOrNull { row[it] }!!.toLong()
        }

    override fun close() {
        runCatching { session?.close() }
        runCatching { calcDetSession?.close() }
    }

    companion object {
        private const val INPUT_NAME = "input1"
        private const val OUTPUT_NAME = "output"

        internal val CALC_CHARS = listOf(
            "0","1","2","3","4","5","6","7","8","9","+","-","x","*","?","/","=","÷"
        )

        fun decodeBase64(imageBase64: String): Bitmap? {
            val cleaned = imageBase64.substringAfter("base64,", imageBase64)
            return runCatching {
                val bytes = Base64.decode(cleaned, Base64.DEFAULT)
                val opts = BitmapFactory.Options().apply { inPremultiplied = false }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }.getOrNull()
        }

        fun outputColsFor(platformId: String): Int? = when (platformId) {
            "yinghua" -> 18
            "cqie" -> 26
            "xuexitong" -> 23
            else -> null
        }

        fun isValidCaptchaText(platformId: String, text: String): Boolean =
            OcrDecoder.isValidCaptchaText(platformId, text)
    }
}
