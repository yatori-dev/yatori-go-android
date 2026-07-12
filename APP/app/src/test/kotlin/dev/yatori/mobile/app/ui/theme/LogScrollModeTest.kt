package dev.yatori.mobile.app.ui.theme

import org.junit.Test
import kotlin.test.assertEquals

class LogScrollModeTest {
    @Test
    fun `missing and invalid values default to realtime`() {
        assertEquals(LogScrollMode.REALTIME, parseLogScrollMode(null))
        assertEquals(LogScrollMode.REALTIME, parseLogScrollMode("invalid"))
    }

    @Test
    fun `none value is restored`() {
        assertEquals(LogScrollMode.NONE, parseLogScrollMode("NONE"))
    }
}
