package io.github.gl1ch5.linuxarm.core

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** A started child process whose output is copied into a log file line by line. */
class LoggedProcess(val name: String, val process: Process, private val pump: Thread) {
    val isAlive get() = process.isAlive
    val pid: Int = Processes.pidOf(process)

    fun waitFor(): Int {
        val code = process.waitFor()
        pump.join(2000)
        return code
    }

    fun destroy() {
        // Kill the whole process tree: proot forwards SIGTERM/SIGKILL to its tracees
        // (--kill-on-exit), and the X server shuts its xstartup child down itself.
        Processes.killTree(pid, OsConstants.SIGTERM)
        process.destroy()
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            Processes.killTree(pid, OsConstants.SIGKILL)
            process.destroyForcibly()
        }
    }
}

object Processes {
    /**
     * Starts [cmd] with exactly [env] (the app's own environment is not inherited) and
     * copies stdout+stderr into logs/[logName]. [onLine] sees every output line.
     */
    fun start(
        name: String,
        cmd: List<String>,
        env: Map<String, String>,
        logName: String,
        cwd: File? = null,
        onLine: ((String) -> Unit)? = null,
    ): LoggedProcess {
        AppLog.i("[$name] exec: ${cmd.joinToString(" ") { quote(it) }}")
        AppLog.d("[$name] env: " + env.entries.joinToString(" ") { "${it.key}=${quote(it.value)}" })
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().clear()
        pb.environment().putAll(env)
        if (cwd != null) pb.directory(cwd)
        val p = pb.start()
        AppLog.appendTo(logName, "=== $name started (pid ${pidOf(p)}) ===")
        val pump = thread(name = "log-$name", isDaemon = true) {
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    AppLog.appendTo(logName, line)
                    onLine?.invoke(line)
                }
            } catch (_: Exception) {
            }
            val code = try { p.waitFor() } catch (_: Exception) { -1 }
            AppLog.appendTo(logName, "=== $name exited with code $code ===")
            AppLog.i("[$name] exited with code $code")
        }
        return LoggedProcess(name, p, pump)
    }

    /** Runs [cmd] to completion and returns (exit code, combined output). */
    fun run(cmd: List<String>, env: Map<String, String>? = null, timeoutSec: Long = 30): Pair<Int, String> {
        return try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            if (env != null) {
                pb.environment().clear(); pb.environment().putAll(env)
            }
            val p = pb.start()
            val out = StringBuilder()
            val reader = thread(isDaemon = true) {
                try { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } catch (_: Exception) {}
            }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                killTree(pidOf(p), OsConstants.SIGKILL)
                p.destroyForcibly()
                reader.join(1000)
                return -1 to (out.toString() + "\n[timeout after ${timeoutSec}s]")
            }
            reader.join(2000)
            p.exitValue() to out.toString()
        } catch (e: Exception) {
            -1 to (e.toString())
        }
    }

    /** java.lang.Process on Android has no pid(); read the private field instead. */
    fun pidOf(p: Process): Int = runCatching {
        val f = p.javaClass.getDeclaredField("pid")
        f.isAccessible = true
        f.getInt(p)
    }.getOrDefault(-1)

    /** Pids of all descendants of [pid], found through /proc/<pid>/stat (own uid only). */
    fun descendants(pid: Int): List<Int> {
        if (pid <= 0) return emptyList()
        val parents = HashMap<Int, MutableList<Int>>()
        File("/proc").listFiles()?.forEach { d ->
            val child = d.name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(d, "stat").readText() }.getOrNull() ?: return@forEach
            // Format: pid (comm) state ppid ...; comm may contain spaces and parens.
            val ppid = stat.substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull() ?: return@forEach
            parents.getOrPut(ppid) { mutableListOf() }.add(child)
        }
        val out = ArrayList<Int>()
        val queue = ArrayDeque(listOf(pid))
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            parents[cur]?.forEach { out += it; queue += it }
        }
        return out
    }

    fun killTree(pid: Int, signal: Int) {
        if (pid <= 0) return
        // Children first so that nothing gets re-parented and lost in between.
        descendants(pid).asReversed().forEach { runCatching { Os.kill(it, signal) } }
        runCatching { Os.kill(pid, signal) }
    }

    fun quote(s: String): String =
        if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "-_./:=,+@%" }) s
        else "'" + s.replace("'", "'\\''") + "'"
}
