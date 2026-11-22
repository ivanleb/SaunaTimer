package com.sauna.tracker.mobile

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.sauna.tracker.shared.CsvExporter
import com.sauna.tracker.shared.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object CsvShare {
    /** Writes one CSV with all [sessions] and opens the Android share sheet (Drive, e-mail, messengers…). */
    suspend fun share(context: Context, sessions: List<Session>) {
        if (sessions.isEmpty()) return
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val name = if (sessions.size == 1) CsvExporter.fileName(sessions[0])
            else "sauna_export_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv"
            File(dir, name).apply { writeText(CsvExporter.toCsv(sessions)) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, file.name))
    }
}
