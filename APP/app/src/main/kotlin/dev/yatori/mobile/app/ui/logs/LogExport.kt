package dev.yatori.mobile.app.ui.logs

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.yatori.mobile.app.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

const val LOG_EXPORT_MIME_TYPE = "application/zip"

suspend fun prepareLogExport(container: AppContainer, context: Context, name: String? = null): Result<File> =
    withContext(Dispatchers.IO) {
        runCatching {
            val cacheDir = File(context.cacheDir, "log-export").apply { mkdirs() }
            container.logStore.exportToZip(cacheDir, name = name, includeJsonl = true)
        }
    }

suspend fun saveLogExport(context: Context, zip: File, uri: Uri): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                copyLogExportTo(zip, out)
            } ?: error("Cannot open destination")
            Unit
        }
    }

internal fun copyLogExportTo(zip: File, out: OutputStream): Long =
    zip.inputStream().use { input -> input.copyTo(out) }

fun shareLogExportIntent(context: Context, zip: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
    return Intent(Intent.ACTION_SEND).apply {
        type = LOG_EXPORT_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

fun shareLogExport(context: Context, zip: File): Result<Unit> =
    runCatching {
        val chooser = Intent.createChooser(shareLogExportIntent(context, zip), "分享日志")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
