package com.yatori.android.engine.slider

import android.graphics.Bitmap
import android.graphics.Color
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 滑块验证码偏移量计算
 * 等价移植 Go XueXiTongSliderAction.go DetectSlideOffset
 * 算法：归一化互相关 (NCC)
 */
@Singleton
class SliderEngine @Inject constructor() {

    /** 计算滑块图片在背景图中的 x 偏移量 */
    fun detectOffset(bg: Bitmap, cutout: Bitmap): Int {
        val bgGray = toGray(bg)
        val cutGray = toGray(cutout)
        val x = normCrossCorrelation(bgGray, cutGray)
        return x - 5   // 等价 Go: return offsetX - 5
    }

    /** 等价 Go toGray */
    private fun toGray(bmp: Bitmap): Array<DoubleArray> {
        val w = bmp.width; val h = bmp.height
        return Array(h) { y ->
            DoubleArray(w) { x ->
                val px = bmp.getPixel(x, y)
                0.299 * Color.red(px) + 0.587 * Color.green(px) + 0.114 * Color.blue(px)
            }
        }
    }

    /** 等价 Go normCrossCorrelation — 返回最佳 x 偏移 */
    private fun normCrossCorrelation(src: Array<DoubleArray>, tpl: Array<DoubleArray>): Int {
        val h1 = src.size; val w1 = src[0].size
        val h2 = tpl.size; val w2 = tpl[0].size
        var bestX = 0; var bestScore = -2.0

        for (y in 0..h1 - h2) {
            for (x in 0..w1 - w2) {
                var sumSrc = 0.0; var sumTpl = 0.0
                var sumSrc2 = 0.0; var sumTpl2 = 0.0; var sumMul = 0.0
                val n = (w2 * h2).toDouble()
                for (j in 0 until h2) for (i in 0 until w2) {
                    val a = src[y + j][x + i]; val b = tpl[j][i]
                    sumSrc += a; sumTpl += b
                    sumSrc2 += a * a; sumTpl2 += b * b; sumMul += a * b
                }
                val mA = sumSrc / n; val mB = sumTpl / n
                val num = sumMul - n * mA * mB
                val den = sqrt((sumSrc2 - n * mA * mA) * (sumTpl2 - n * mB * mB) + 1e-9)
                val score = num / den
                if (score > bestScore) { bestScore = score; bestX = x }
            }
        }
        return bestX
    }
}
