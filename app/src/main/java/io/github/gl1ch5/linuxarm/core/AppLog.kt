package io.github.gl1ch5.linuxarm.core

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logs to logcat and to files under filesDir/logs so that the user can share them
 * from the Logs screen. app.log holds the app's own messages; every running process
 * (X server, proot session, diagnostics) gets its own file.
 */
object AppLog {
    private const val TAG = "LinuxArm"
    private const val MAX_SIZE = 4L * 1024 * 1024
    private lateinit var dir: File
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(logDir: File) {
        dir = logDir.apply { mkdirs() }
    }

    fun file(name: String) = File(dir, name)

    fun allFiles(): List<File> =
        dir.listFiles { f -> f.isFile && (f.name.endsWith(".log") || f.name.endsWith(".log.1")) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun d(msg: String) = write('D', msg, null)
    fun i(msg: String) = write('I', msg, null)
    fun w(msg: String, t: Throwable? = null) = write('W', msg, t)
    fun e(msg: String, t: Throwable? = null) = write('E', msg, t)

    @Synchronized
    private fun write(level: Char, msg: String, t: Throwable?) {
        when (level) {
            'D' -> Log.d(TAG, msg, t)
            'I' -> Log.i(TAG, msg, t)
            'W' -> Log.w(TAG, msg, t)
            else -> Log.e(TAG, msg, t)
        }
        if (!::dir.isInitialized) return
        appendTo("app.log", "${fmt.format(Date())} $level $msg" +
                (t?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
    }

    /** Appends a line to a named log, rotating it to name.1 when it grows too big. */
    @Synchronized
    fun appendTo(name: String, line: String) {
        if (!::dir.isInitialized) return
        try {
            val f = File(dir, name)
            if (f.length() > MAX_SIZE) {
                f.renameTo(File(dir, "$name.1"))
            }
            FileWriter(f, true).use { it.write(line); it.write("\n") }
        } catch (_: Exception) {
        }
    }

    /** Starts a fresh log for a new run of [name], keeping the previous run as name.1. */
    @Synchronized
    fun rotate(name: String) {
        if (!::dir.isInitialized) return
        val f = File(dir, name)
        if (f.exists()) f.renameTo(File(dir, "$name.1"))
    }
}
