package dev.yatori.mobile.app.ui.logs

import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class LogExportTest {
    @Test
    fun `copyLogExportTo writes the selected zip bytes`() {
        val file = Files.createTempFile("yatori-log-export", ".zip").toFile()
        val bytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04, 1, 2, 3, 4)
        file.writeBytes(bytes)

        val out = ByteArrayOutputStream()
        val copied = copyLogExportTo(file, out)

        assertEquals(bytes.size.toLong(), copied)
        assertContentEquals(bytes, out.toByteArray())
        file.delete()
    }
}
