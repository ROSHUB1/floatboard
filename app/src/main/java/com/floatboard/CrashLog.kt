package com.floatboard

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves the stack trace of the last crash to a file so Settings can show it (copy and send it for debugging). */
object CrashLog {
    private var installed = false
    private lateinit var file: File

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        file = File(context.applicationContext.filesDir, "last_crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file.writeText("$stamp  thread=${thread.name}\n$sw")
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
        installed = true
    }

    fun read(): String? = try {
        if (::file.isInitialized && file.exists()) file.readText().takeIf { it.isNotBlank() } else null
    } catch (e: Exception) {
        null
    }

    fun clear() {
        try { if (::file.isInitialized) file.delete() } catch (_: Exception) {}
    }
}
