package com.yatori.android

import com.yatori.android.engine.ai.AiMessage
import com.yatori.android.engine.ai.QuestionMessageBuilder
import com.yatori.android.engine.ai.QuestionType
import com.yatori.android.engine.slider.SliderEngine
import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test

class AiEngineTest {

    @Test fun `single choice message contains system prompt and user content`() {
        val msgs = QuestionMessageBuilder.build(
            QuestionType.SINGLE, "新中国是什么时候成立的？",
            listOf("1949年10月5日", "1949年10月1日", "1949年09月1日", "2002年10月1日")
        )
        assertTrue(msgs.any { it.role == "system" })
        val userMsg = msgs.last()
        assertEquals("user", userMsg.role)
        assertTrue(userMsg.content.contains("新中国"))
        assertTrue(userMsg.content.contains("A."))
        assertTrue(userMsg.content.contains("B."))
    }

    @Test fun `multi choice message contains multi system prompt`() {
        val msgs = QuestionMessageBuilder.build(
            QuestionType.MULTI, "以下哪些是正确的？",
            listOf("选项A", "选项B", "选项C")
        )
        assertTrue(msgs.any { it.role == "system" && it.content.contains("多选题") })
    }

    @Test fun `true false message uses judge system`() {
        val msgs = QuestionMessageBuilder.build(QuestionType.JUDGE, "地球是圆的？", listOf("正确", "错误"))
        assertTrue(msgs.any { it.role == "system" && it.content.contains("正确") })
    }

    @Test fun `match question formats groups correctly`() {
        val msgs = QuestionMessageBuilder.build(
            QuestionType.MATCH, "连线题", listOf("[1]桑代克", "[1]威特金", "[2]迷箱", "[2]棒框")
        )
        val userContent = msgs.last().content
        assertTrue(userContent.contains("组别一"))
        assertTrue(userContent.contains("组别二"))
        assertTrue(userContent.contains("桑代克"))
    }

    @Test fun `question type from string`() {
        assertEquals(QuestionType.SINGLE, QuestionType.fromString("单选题"))
        assertEquals(QuestionType.MULTI,  QuestionType.fromString("多选题"))
        assertEquals(QuestionType.JUDGE,  QuestionType.fromString("判断题"))
        assertEquals(QuestionType.FILL,   QuestionType.fromString("填空题"))
    }
}
