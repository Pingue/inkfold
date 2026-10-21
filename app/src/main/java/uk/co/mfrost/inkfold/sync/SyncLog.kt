package uk.co.mfrost.inkfold.sync

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small rolling history of what the last few sync passes did, kept on disk
 * so it survives process death and can be viewed from within the app (e.g.
 * on a device without adb handy) instead of only through Logcat.
 */
class SyncLog(context: Context) {

    private val file = File(context.filesDir, "sync-log.txt")

    fun add(message: String) {
        synchronized(lock) {
            runCatching {
                val line = "${TIMESTAMP_FORMAT.format(Date())}  $message"
                val lines = (if (file.exists()) file.readLines() else emptyList()) + line
                file.writeText(lines.takeLast(MAX_LINES).joinToString("\n"))
            }
        }
    }

    fun read(): String = synchronized(lock) {
        runCatching { file.readText() }.getOrDefault("")
    }

    fun clear() {
        synchronized(lock) {
            runCatching { file.delete() }
        }
    }

    private companion object {
        const val MAX_LINES = 200
        val lock = Any()
        val TIMESTAMP_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    }
}
