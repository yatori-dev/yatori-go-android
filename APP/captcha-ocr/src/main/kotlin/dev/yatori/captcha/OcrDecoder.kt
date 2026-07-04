package dev.yatori.captcha

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Decodes raw ONNX output indices into captcha text using the ddddocr charcode table,
 * and contains platform-specific captcha text normalization.
 */
class OcrDecoder(charCodeJson: String) {

    private val table: List<String> =
        Gson().fromJson(charCodeJson, object : TypeToken<List<String>>() {}.type)

    val tableSize: Int get() = table.size

    /** Maps non-blank output indices to characters and concatenates them. */
    fun decode(indices: LongArray): String =
        indices.filter { it != 0L }
            .joinToString("") { table.getOrElse(it.toInt()) { "" } }

    /** Parses a recognized arithmetic captcha string such as "3+5=?" or "12-4". */
    fun parseArithmetic(text: String): Int? {
        val cleaned = text.replace("=", "").replace("?", "").trim()
        val m = ARITHMETIC.find(cleaned) ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val op = m.groupValues[2]
        val b = m.groupValues[3].toIntOrNull() ?: return null
        return when (op) {
            "+" -> a + b
            "-" -> a - b
            "*", "x", "X" -> a * b
            else -> null
        }
    }

    /**
     * Returns a submit-ready captcha string only when the OCR result satisfies the
     * platform format. Invalid/partial recognitions return null and must be retried
     * without calling the Go login/submit API.
     */
    fun normalizeCaptchaText(platformId: String, raw: String): String? {
        val compact = raw.filterNot { it.isWhitespace() }
        val normalized = when (platformId) {
            "qingshuxuetang" -> parseArithmetic(compact)?.toString()
            "yinghua", "cqie", "xuexitong" -> compact.filter { it.isAsciiLetterOrDigit() }
            else -> compact
        } ?: return null
        return normalized.takeIf { isValidCaptchaText(platformId, it) }
    }

    companion object {
        private val ARITHMETIC = Regex("""(\d+)\s*([+\-*xX])\s*(\d+)""")
        private val ASCII_ALNUM_4 = Regex("""[A-Za-z0-9]{4}""")

        fun isValidCaptchaText(platformId: String, text: String): Boolean = when (platformId) {
            "yinghua", "cqie", "xuexitong" -> ASCII_ALNUM_4.matches(text)
            "qingshuxuetang" -> text.matches(Regex("""-?\d+"""))
            else -> text.isNotBlank()
        }
    }
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in '0'..'9' || this in 'A'..'Z' || this in 'a'..'z'
