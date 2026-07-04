package dev.yatori.mobile.app.ui.courses

import org.junit.Test
import kotlin.test.assertEquals

class AccountCardTitleTest {
    @Test
    fun `account is primary title when remark is blank`() {
        val title = accountCardTitle("student2026@example.com", "学习通", "")

        assertEquals("student2026@example.com", title.title)
        assertEquals("学习通", title.subtitle)
    }

    @Test
    fun `remark title keeps a useful account snippet`() {
        val title = accountCardTitle("student2026@example.com", "学习通", "高数号")

        assertEquals("高数号(stu....26@example.com)", title.title)
        assertEquals("学习通", title.subtitle)
    }

    @Test
    fun `short account is not over-hidden with remark`() {
        val title = accountCardTitle("1234567", "英华", "小号")

        assertEquals("小号(1234567)", title.title)
    }
}
