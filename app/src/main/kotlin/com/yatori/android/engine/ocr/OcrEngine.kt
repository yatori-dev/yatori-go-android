package com.yatori.android.engine.ocr

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数字验证码 OCR 引擎
 * 等价移植 Go ddddocr-go SemiOCRVerification / AutoOCRVerification
 * 模型: assets/common_old.onnx  字符表: assets/charcode.json
 */
@Singleton
class OcrEngine @Inject constructor(@ApplicationContext private val ctx: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val session: OrtSession by lazy {
        env.createSession(ctx.assets.open("common_old.onnx").readBytes(), OrtSession.SessionOptions())
    }

    /** 字符映射表，等价 Go getCharCode() 解析结果 */
    private val charTable: List<String> by lazy {
        Gson().fromJson(
            ctx.assets.open("charcode.json").bufferedReader().readText(),
            object : TypeToken<List<String>>() {}.type
        )
    }

    /**
     * 自动推断输出维度识别验证码，等价 Go AutoOCRVerification
     * 宽度140px → 输出列23；其他 → 输出列30（来自 Go XueXiTongChapterAction）
     */
    fun recognize(bitmap: Bitmap): String {
        val (data, inShape) = preprocess(bitmap)
        // 根据宽度猜初始输出维度（等价 Go 代码中的 shape 判断）
        var outLen = if (bitmap.width == 140) 23L else 30L
        repeat(4) {
            runCatching { return runInference(data, inShape, outLen) }
                .onFailure { e ->
                    // 从 ONNX 报错中解析实际输出维度（等价 Go 自动重试逻辑）
                    val m = Regex("""Requested shape:\{(\d+),(\d+)\}""").find(e.message ?: "")
                    if (m != null) outLen = m.groupValues[2].toLong()
                    else outLen = if (outLen == 23L) 30L else 16L
                }
        }
        return ""
    }

    /** 半自动识别，调用方指定输出列数，等价 Go SemiOCRVerification */
    fun recognizeSemi(bitmap: Bitmap, outputCols: Int): String {
        val (data, inShape) = preprocess(bitmap)
        return runCatching { runInference(data, inShape, outputCols.toLong()) }
            .onFailure { android.util.Log.e("OcrEngine", "recognizeSemi failed cols=$outputCols", it) }
            .getOrDefault("")
    }

    /** Bitmap → 灰度归一化 float32，等价 Go ImageToGrayFloatArray(ConvertToGray(ResizeImage())) */
    private fun preprocess(bitmap: Bitmap): Pair<FloatArray, LongArray> {
        val h = 64
        val w = (64 * bitmap.width.toFloat() / bitmap.height).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val arr = FloatArray(w * h)
        var idx = 0
        for (y in 0 until h) for (x in 0 until w) {
            val px = scaled.getPixel(x, y)
            arr[idx++] = (0.299f * Color.red(px) + 0.587f * Color.green(px) + 0.114f * Color.blue(px)) / 255f
        }
        if (scaled != bitmap) scaled.recycle()
        return arr to longArrayOf(1, 1, 64, w.toLong())
    }

    private fun runInference(data: FloatArray, inShape: LongArray, outLen: Long): String {
        val buf = java.nio.FloatBuffer.wrap(data)
        OnnxTensor.createTensor(env, buf, inShape).use { input ->
            session.run(mapOf("input1" to input)).use { result ->
                val raw = result[0].value
                val out: LongArray = when (raw) {
                    is LongArray -> raw
                    is Array<*> -> (raw as Array<LongArray>).firstOrNull() ?: LongArray(0)
                    else -> LongArray(0)
                }
                android.util.Log.d("OcrEngine", "raw indices=${out.toList()}, inShape=${inShape.toList()}, dataMin=${data.min()}, dataMax=${data.max()}")
                return out.filter { it != 0L }
                    .joinToString("") { charTable.getOrElse(it.toInt()) { "" } }
            }
        }
    }
}
