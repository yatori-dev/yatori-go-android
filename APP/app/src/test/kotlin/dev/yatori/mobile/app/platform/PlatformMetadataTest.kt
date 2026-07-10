package dev.yatori.mobile.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformMetadataTest {
    @Test
    fun `enaea and ttcdw display names identify their adapters`() {
        assertEquals("学习公社 / 网络党校（ENAEA）", platformDisplayName("enaea"))
        assertEquals("学习公社（TTCDW）", platformDisplayName("ttcdw"))
    }
}
