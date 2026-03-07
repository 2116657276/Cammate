package com.liveaicapture.mvp.log

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppLogger(private val context: Context) {
    private val lock = Any()
    private val timestampFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val maxBytes = 2 * 1024 * 1024L
    private val logDir: File by lazy { File(context.filesDir, "logs").apply { mkdirs() } }
    private val logFile: File by lazy { File(logDir, "client.log") }
    private val backupFile: File by lazy { File(logDir, "client.log.1") }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append("INFO", tag, message, null)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        append("WARN", tag, message, null)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
        append("ERROR", tag, message, throwable)
    }

    private fun append(level: String, tag: String, message: String, throwable: Throwable?) {
        synchronized(lock) {
            try {
                rotateIfNeeded()
                val ts = timestampFormatter.format(Date())
                val line = buildString {
                    append(ts)
                    append(" | ")
                    append(level)
                    append(" | ")
                    append(tag)
                    append(" | ")
                    append(message)
                    if (throwable != null) {
                        append(" | ")
                        append(throwable.javaClass.simpleName)
                        append(": ")
                        append(throwable.message ?: "")
                    }
                    append('\n')
                }
                logFile.appendText(line)
            } catch (_: Exception) {
                // Intentionally ignore file logging failures to avoid affecting app flow.
            }
        }
    }

    private fun rotateIfNeeded() {
        if (!logFile.exists()) return
        if (logFile.length() < maxBytes) return
        if (backupFile.exists()) {
            backupFile.delete()
        }
        logFile.renameTo(backupFile)
    }
}

object AppLog {
    @Volatile
    private var logger: AppLogger? = null

    fun get(context: Context): AppLogger {
        val existing = logger
        if (existing != null) return existing
        return synchronized(this) {
            logger ?: AppLogger(context.applicationContext).also { logger = it }
        }
    }
}

