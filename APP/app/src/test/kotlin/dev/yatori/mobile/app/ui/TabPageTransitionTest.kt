package dev.yatori.mobile.app.ui

import dev.yatori.mobile.app.ui.theme.PageAnim
import org.junit.Test
import kotlin.test.assertEquals

class TabPageTransitionTest {
    @Test
    fun `slide mode moves inactive pages horizontally without fading`() {
        val target = tabPageTransitionTarget(pageIndex = 0, selectedIndex = 1, anim = PageAnim.SLIDE)

        assertEquals(-1f, target.offsetPages)
        assertEquals(1f, target.alpha)
    }

    @Test
    fun `fade mode keeps pages in place and fades inactive pages`() {
        val target = tabPageTransitionTarget(pageIndex = 0, selectedIndex = 1, anim = PageAnim.FADE)

        assertEquals(0f, target.offsetPages)
        assertEquals(0f, target.alpha)
    }
}
