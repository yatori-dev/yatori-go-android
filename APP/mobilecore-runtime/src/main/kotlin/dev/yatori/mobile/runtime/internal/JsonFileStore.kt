package dev.yatori.mobile.runtime.internal

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object JsonFileStore {
    /** Atomic-ish write: write to .tmp then replace. */
    fun write(file: File, json: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json, Charsets.UTF_8)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun read(file: File): String? = if (file.exists()) file.readText(Charsets.UTF_8) else null

    fun delete(file: File): Boolean = file.exists() && file.delete()
}
