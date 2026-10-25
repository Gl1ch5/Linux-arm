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

    /** All logs, [COMBINED] first, then the newest. */
    fun allFiles(): List<File> =
        dir.listFiles { f -> f.isFile && (f.name.endsWith(".log") || f.name.endsWith(".log.1")) }
            ?.sortedWith(compareBy<File> { it.name != COMBINED }.thenByDescending { it.lastModified() })
            ?: emptyList()

    const val COMBINED = "all.log"

    /**
     * Writes [COMBINED]: the device summary and every other log in one file, so that one
     * file is enough to share. Order: app.log, the session logs, X server, diagnostics,
     * logcat, then the previous runs (*.log.1). Runs of identical lines are collapsed.
     */
    @Synchronized
    fun buildCombined(header: String): File {
        val out = File(dir, COMBINED)
        val files = dir.listFiles { f -> f.isFile && f.name != COMBINED && (f.name.endsWith(".log") || f.name.endsWith(".log.1")) }
            .orEmpty()
        fun rank(f: File): Int {
            val n = f.name.removeSuffix(".1")
            val base = when {
                n == "app.log" -> 0
                n.startsWith("session-") -> 1
                n == "xserver.log" -> 2
                n == "diagnostics.log" -> 3
                n == "logcat.log" -> 4
                else -> 5
            }
            return base + if (f.name.endsWith(".1")) 10 else 0
        }
        val sorted = files.sortedWith(compareBy<File>({ rank(it) }, { -it.lastModified() }))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        out.bufferedWriter().use { w ->
            w.write("##### Linux ARM: все логи одним файлом (${stamp.format(Date())})\n")
            w.write(header.trimEnd()); w.write("\n")
            w.write("Файлы: ${sorted.joinToString(", ") { it.name }}\n")
            for (f in sorted) {
                w.write("\n\n##### ${f.name} (${f.length() / 1024} КБ, изменён ${stamp.format(Date(f.lastModified()))})")
                if (f.name.endsWith(".1")) w.write(" — предыдущий запуск")
                w.write("\n")
                // logcat is huge and mostly other apps' noise: its tail is what matters.
                val limit = if (f.name.startsWith("logcat")) 4000 else 20000
                val lines = runCatching { f.readLines() }.getOrElse { listOf("(не читается: ${it.message})") }
                if (lines.size > limit) w.write("… пропущено ${lines.size - limit} строк в начале\n")
                var prev: String? = null
                var repeats = 0
                fun flush() {
                    if (repeats > 0) w.write("    … повтор ещё ${repeats} раз\n")
                    repeats = 0
                }
                for (line in lines.takeLast(limit)) {
                    if (line == prev) { repeats++; continue }
                    flush()
                    w.write(line); w.write("\n")
                    prev = line
                }
                flush()
            }
        }
        return out
    }

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
