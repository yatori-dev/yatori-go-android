package com.yatori.android.engine.face

import android.graphics.Bitmap
import android.graphics.Color
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 人脸绕过 — 随机 LSB 像素扰动
 * 等价移植 Go utils/ImageUtils.go ImageRGBDisturb
 * 原理：随机翻转每个像素 RGB 各通道的最低有效位，使人脸识别略微失效
 */
@Singleton
class FaceBypassEngine @Inject constructor() {

    fun disturb(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                val px = out.getPixel(x, y)
                val r = (Color.red(px)   and 0xFE) or Random.nextInt(2)
                val g = (Color.green(px) and 0xFE) or Random.nextInt(2)
                val b = (Color.blue(px)  and 0xFE) or Random.nextInt(2)
                out.setPixel(x, y, Color.argb(Color.alpha(px), r, g, b))
            }
        }
        return out
    }
}
