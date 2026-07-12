package dev.yatori.mobile.app.ocr

import java.io.Closeable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class IdleCloseableResourceTest {
    @Test
    fun `resource closes only after idle timeout`() = runTest {
        var closes = 0
        val resource = IdleCloseableResource(
            scope = backgroundScope,
            idleTimeoutMillis = 60_000L,
            create = { Closeable { closes++ } },
        )

        resource.use { }
        advanceTimeBy(59_999L)
        runCurrent()
        assertEquals(0, closes)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(1, closes)
    }
}
