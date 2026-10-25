package io.github.gl1ch5.linuxarm.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Works without any special permission. Android's phantom process killer kills child
 * processes of apps once there are more than 32 of them, starting with the oldest ones,
 * i.e. proot and the session itself, which takes the whole desktop down. While the
 * restriction is active, this keeps our own count under the limit by closing background
 * helpers that a desktop restarts on demand (thumbnailers, volume monitors, indexers),
 * so that Android never gets to choose.
 */
class ProcessGuard(private val scope: CoroutineScope) {
    data class Proc(val pid: Int, val name: String, val startTicks: Long)

    private val _count = MutableStateFlow(0)
    /** Child processes of the app (everything but the app's own processes). */
    val count: StateFlow<Int> = _count
    private var job: Job? = null

    fun start(isRestricted: () -> Boolean) {
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            var restricted = isRestricted()
            var ticks = 0
            while (isActive) {
                // The setting can change while a session runs; re-check now and then.
                if (++ticks % 20 == 0) restricted = isRestricted()
                val procs = runCatching { children() }.getOrElse { emptyList() }
                _count.value = procs.size
                if (restricted && procs.size > SOFT_LIMIT) trim(procs)
                delay(if (restricted) 1500 else 5000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _count.value = 0
    }

    private fun trim(procs: List<Proc>) {
        val excess = procs.size - SOFT_LIMIT
        val victims = procs.filter { p -> EXPENDABLE.any { p.name.startsWith(it) } }
            .sortedByDescending { it.startTicks }
            .take(excess)
        for (v in victims) {
            AppLog.i("process guard: ${procs.size} child processes (limit ${LIMIT}), closing ${v.name} (${v.pid})")
            android.os.Process.sendSignal(v.pid, SIGTERM)
        }
        if (victims.size < excess) AppLog.w("process guard: ${procs.size} child processes and nothing left to close")
    }

    companion object {
        /** Android's default max_phantom_processes. */
        const val LIMIT = 32
        /** Leaves room for the app's own short-lived helpers (logcat, checks, shortcuts). */
        const val SOFT_LIMIT = 28
        private const val SIGTERM = 15

        /** /proc/<pid>/comm prefixes (15 characters at most) of helpers that are safe to close. */
        private val EXPENDABLE = listOf(
            "tumblerd", "gvfsd", "gvfs-", "at-spi", "localsearch", "tracker-", "evolution-",
            "goa-", "gsd-sharing", "gsd-print-notif", "gsd-wacom", "gsd-smartcard", "gsd-usb-protect",
            "gsd-rfkill", "gsd-housekeepin", "gsd-a11y-settin", "zeitgeist", "gnome-software",
            "packagekitd", "update-notifier", "xfce4-notifyd", "xfce4-power-man", "light-locker",
            "baloo_file", "kdeconnectd", "akonadi", "geoclue", "colord", "dconf-service",
        )

        private val myUid = android.os.Process.myUid()

        /** Processes of our uid that are not app processes (those are named after the package). */
        fun children(): List<Proc> {
            val pkg = "io.github.gl1ch5.linuxarm"
            return File("/proc").listFiles().orEmpty().mapNotNull { dir ->
                val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
                if (pid == android.os.Process.myPid()) return@mapNotNull null
                runCatching {
                    val uid = File(dir, "status").useLines { l -> l.first { it.startsWith("Uid:") } }
                        .split(Regex("\\s+"))[1].toInt()
                    if (uid != myUid) return@runCatching null
                    val cmdline = File(dir, "cmdline").readText().substringBefore('\u0000')
                    if (cmdline.startsWith(pkg)) return@runCatching null
                    val stat = File(dir, "stat").readText()
                    // Fields after "(comm)": state is field 3, starttime is field 22.
                    val name = stat.substring(stat.indexOf('(') + 1, stat.lastIndexOf(')'))
                    val rest = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
                    Proc(pid, name, rest.getOrNull(19)?.toLongOrNull() ?: 0L)
                }.getOrNull()
            }
        }
    }
}
