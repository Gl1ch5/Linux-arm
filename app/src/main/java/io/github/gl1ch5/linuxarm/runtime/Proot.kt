package io.github.gl1ch5.linuxarm.runtime

import io.github.gl1ch5.linuxarm.core.AppPaths
import java.io.File

/**
 * Builds proot command lines. The layout follows proot-distro: real /dev, /proc and
 * /sys bound in, fake /proc entries where Android hides the real ones, and a clean
 * environment inside the guest (set through `env -i`, so nothing from the app leaks in).
 */
class Proot(private val paths: AppPaths) {
    data class Bind(val host: String, val guest: String = host)

    /** Environment of the proot process itself (not the guest). */
    fun hostEnv(): Map<String, String> = mapOf(
        "PROOT_LOADER" to paths.prootLoader.path,
        "PROOT_TMP_DIR" to paths.prootTmp.path,
        "PATH" to "/system/bin",
        "ANDROID_ROOT" to "/system",
        "ANDROID_DATA" to "/data",
    )

    fun command(
        distroId: String,
        guestEnv: Map<String, String>,
        argv: List<String>,
        extraBinds: List<Bind> = emptyList(),
        cwd: String = "/root",
    ): List<String> {
        val rootfs = paths.rootfs(distroId)
        paths.prootTmp.mkdirs()
        val cmd = mutableListOf(
            paths.proot.path,
            "--kill-on-exit",
            "--link2symlink",
            "--sysvipc",
            "-L",
            "--root-id",
            "--kernel-release=6.6.0-linux-arm",
            "--rootfs=${rootfs.path}",
            "--cwd=$cwd",
        )
        val binds = mutableListOf(
            Bind("/dev"),
            Bind("/proc"),
            Bind("/sys"),
            Bind("/dev/urandom", "/dev/random"),
            Bind("/proc/self/fd", "/dev/fd"),
            Bind("/proc/self/fd/0", "/dev/stdin"),
            Bind("/proc/self/fd/1", "/dev/stdout"),
            Bind("/proc/self/fd/2", "/dev/stderr"),
            Bind(File(rootfs, "tmp").path, "/dev/shm"),
        )
        binds += FakeProc.binds(paths.fakeProc(distroId))
        binds += extraBinds
        binds.forEach { cmd += "--bind=${it.host}:${it.guest}" }
        cmd += "/usr/bin/env"
        cmd += "-i"
        guestEnv.forEach { (k, v) -> cmd += "$k=$v" }
        cmd += argv
        return cmd
    }

    companion object {
        fun baseGuestEnv(display: String?): MutableMap<String, String> = linkedMapOf(
            "HOME" to "/root",
            "USER" to "root",
            "LOGNAME" to "root",
            "SHELL" to "/bin/bash",
            "LANG" to "C.UTF-8",
            "TERM" to "xterm-256color",
            "TMPDIR" to "/tmp",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "XDG_RUNTIME_DIR" to "/tmp/runtime-root",
            // Fewer background processes, see ProcessGuard: no accessibility bus and no
            // gvfs volume monitors (five processes under GNOME), neither works in proot.
            "NO_AT_BRIDGE" to "1",
            "GTK_A11Y" to "none",
            "GIO_USE_VOLUME_MONITOR" to "unix",
        ).apply { if (display != null) put("DISPLAY", display) }
    }
}

/**
 * Android denies apps access to several /proc files that Linux tools expect. Like
 * proot-distro, bind static replacements over them, but only where the real file is
 * not readable.
 */
object FakeProc {
    private val files = mapOf(
        "loadavg" to "0.12 0.07 0.02 2/165 765\n",
        "stat" to """
            cpu  1957 0 2877 93280 262 342 254 87 0 0
            cpu0 31 0 226 12027 82 10 4 9 0 0
            intr 63361 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
            ctxt 38014093
            btime 1694292441
            processes 26442
            procs_running 1
            procs_blocked 0
            softirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677
        """.trimIndent() + "\n",
        "uptime" to "124.08 932.80\n",
        "version" to "Linux version 6.6.0-linux-arm (builder@linux-arm) (gcc version 13.2.0) #1 SMP PREEMPT\n",
        "vmstat" to "nr_free_pages 136791\nnr_zone_inactive_anon 33960\nnr_zone_active_anon 105716\npgpgin 890508\npgpgout 0\n",
        "sys/kernel/cap_last_cap" to "40\n",
        "sys/fs/inotify/max_user_watches" to "4096\n",
    )

    fun binds(dir: File): List<Proot.Bind> = files.mapNotNull { (name, content) ->
        val real = File("/proc/$name")
        if (real.canRead() && runCatching { real.readText(); true }.getOrDefault(false)) return@mapNotNull null
        val fake = File(dir, name.replace('/', '_'))
        if (!fake.exists()) {
            dir.mkdirs(); fake.writeText(content)
        }
        Proot.Bind(fake.path, "/proc/$name")
    }
}
