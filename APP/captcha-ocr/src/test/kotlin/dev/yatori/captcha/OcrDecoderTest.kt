package dev.yatori.captcha

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OcrDecoderTest {

    // index 0 = blank (dropped), then a few mapped chars
    private val decoder = OcrDecoder("""["", "a", "b", "c", "1", "2", "3"]""")

    @Test
    fun `decode drops blank index and maps the rest`() {
        assertEquals("ab3", decoder.decode(longArrayOf(1, 2, 0, 0, 6)))
    }

    @Test
    fun `decode empty when all blank`() {
        assertEquals("", decoder.decode(longArrayOf(0, 0, 0)))
    }

    @Test
    fun `decode tolerates out-of-range indices`() {
        assertEquals("a", decoder.decode(longArrayOf(1, 99)))
    }

    @Test
    fun `parseArithmetic handles addition subtraction and multiplication`() {
        assertEquals(8, decoder.parseArithmetic("3+5=?"))
        assertEquals(8, decoder.parseArithmetic("12 - 4"))
        assertEquals(20, decoder.parseArithmetic("4x5"))
    }

    @Test
    fun `parseArithmetic returns null for non-arithmetic`() {
        assertNull(decoder.parseArithmetic("abcd"))
    }

    @Test
    fun `normalizeCaptchaText accepts only full yinghua captcha`() {
        assertEquals("a1B2", decoder.normalizeCaptchaText("yinghua", " a1 B2 "))
        assertNull(decoder.normalizeCaptchaText("yinghua", "a1B"))
        assertNull(decoder.normalizeCaptchaText("yinghua", "a1B2C"))
        assertNull(decoder.normalizeCaptchaText("yinghua", "验证码错"))
        assertNull(decoder.normalizeCaptchaText("yinghua", "１２AＢ"))
        assertNull(decoder.normalizeCaptchaText("yinghua", "A1_B"))
    }

    @Test
    fun `normalizeCaptchaText solves qingshuxuetang arithmetic`() {
        assertEquals("7", decoder.normalizeCaptchaText("qingshuxuetang", "3+4=?"))
        assertNull(decoder.normalizeCaptchaText("qingshuxuetang", "abcd"))
    }
}
