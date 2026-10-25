package io.github.gl1ch5.linuxarm.runtime

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import io.github.gl1ch5.linuxarm.core.AppLog
import io.github.gl1ch5.linuxarm.x11.XServerService
import kotlinx.coroutines.delay
import java.io.File

/** Starts/stops the embedded X server, which lives in the ":xserver" process. */
class XServer(private val context: Context) {
    private var socket: File? = null

    val isRunning get() = xserverPid() != null

    /**
     * Starts the server with its socket in [rootfs]/tmp/.X11-unix, i.e. at
     * /tmp/.X11-unix inside the guest, and keyboard maps / fonts from that rootfs.
     */
    suspend fun start(rootfs: File, display: Int = 0, extraArgs: List<String> = emptyList()): Boolean {
        stop()
        val tmp = File(rootfs, "tmp")
        val sock = File(tmp, ".X11-unix/X$display")
        File(tmp, ".X11-unix").mkdirs()
        sock.delete()
        File(tmp, ".X$display-lock").delete()
        socket = sock

        val xkb = findXkb(rootfs)?.path
        if (xkb == null) AppLog.w("no XKB data in the rootfs (usr/share/X11/xkb), the X server will likely fail")
        val args = (listOf(":$display", "-ac") + extraArgs).toTypedArray()
        val token = System.nanoTime().toString()
        XServerService.setCurrentToken(context, token)
        context.startService(
            Intent(context, XServerService::class.java)
                .setAction(XServerService.ACTION_START)
                .putExtra(XServerService.EXTRA_TOKEN, token)
                .putExtra(XServerService.EXTRA_TMPDIR, tmp.path)
                .putExtra(XServerService.EXTRA_XKB, xkb)
                .putExtra(XServerService.EXTRA_ARGS, args)
        )
        repeat(150) { i ->
            if (sock.exists()) {
                AppLog.i("X server is listening on $sock (pid ${xserverPid()})")
                return true
            }
            if (i > 30 && xserverPid() == null) {
                AppLog.e("X server process is gone before creating its socket, see xserver.log")
                return false
            }
            delay(100)
        }
        AppLog.e("X server socket did not appear in 15 s, see xserver.log")
        return false
    }

    fun stop() {
        // A server that died while starting is still scheduled for a restart by Android,
        // with its old start command; stopService cancels that even with no process.
        XServerService.setCurrentToken(context, "")
        runCatching { context.stopService(Intent(context, XServerService::class.java)) }
        val pid = xserverPid() ?: return
        AppLog.i("stopping X server (pid $pid)")
        runCatching {
            context.startService(Intent(context, XServerService::class.java).setAction(XServerService.ACTION_STOP))
        }
        // Make sure it is gone even if the service did not get the stop command in time.
        Thread.sleep(300)
        xserverPid()?.let { Process.killProcess(it) }
        socket?.delete()
    }

    /**
     * Keymaps of [rootfs]. Symlinks are resolved inside the rootfs (an absolute link points
     * into the guest, not at Android's root). Falls back to another installed distro's data.
     */
    private fun findXkb(rootfs: File): File? {
        val candidates = listOf("usr/share/X11/xkb", "usr/share/xkeyboard-config-2")
        candidates.firstNotNullOfOrNull { resolveInGuest(rootfs, it)?.takeIf { d -> File(d, "rules").isDirectory } }
            ?.let { return it }
        val others = rootfs.parentFile?.parentFile?.listFiles().orEmpty()
            .map { File(it, "rootfs") }.filter { it != rootfs }
        return others.firstNotNullOfOrNull { other ->
            candidates.firstNotNullOfOrNull { resolveInGuest(other, it)?.takeIf { d -> File(d, "rules").isDirectory } }
        }?.also { AppLog.w("no XKB data in $rootfs, using $it") }
    }

    private fun resolveInGuest(rootfs: File, path: String, depth: Int = 0): File? {
        if (depth > 16) return null
        var cur = rootfs
        val parts = path.split('/').filter { it.isNotEmpty() }
        for ((i, part) in parts.withIndex()) {
            val next = File(cur, part)
            val target = runCatching { android.system.Os.readlink(next.path) }.getOrNull()
            if (target == null) { cur = next; continue }
            val rest = parts.drop(i + 1).joinToString("/")
            val guestBase = cur.relativeTo(rootfs).path
            val resolved = if (target.startsWith("/")) target else "$guestBase/$target"
            return resolveInGuest(rootfs, normalize("$resolved/$rest"), depth + 1)
        }
        return cur.takeIf { it.exists() }
    }

    private fun normalize(path: String): String {
        val out = ArrayDeque<String>()
        for (p in path.split('/')) when (p) {
            "", "." -> {}
            ".." -> out.removeLastOrNull()
            else -> out.addLast(p)
        }
        return out.joinToString("/")
    }

    private fun xserverPid(): Int? = context.getSystemService(ActivityManager::class.java).runningAppProcesses
        ?.firstOrNull { it.processName.endsWith(XServerService.PROCESS_SUFFIX) }?.pid
}
