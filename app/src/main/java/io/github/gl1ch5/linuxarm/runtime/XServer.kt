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

        val xkb = File(rootfs, "usr/share/X11/xkb").takeIf { it.isDirectory }?.path
        if (xkb == null) AppLog.w("no XKB data in the rootfs (usr/share/X11/xkb), the X server will likely fail")
        val args = (listOf(":$display", "-ac") + extraArgs).toTypedArray()
        context.startService(
            Intent(context, XServerService::class.java)
                .setAction(XServerService.ACTION_START)
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

    private fun xserverPid(): Int? = context.getSystemService(ActivityManager::class.java).runningAppProcesses
        ?.firstOrNull { it.processName.endsWith(XServerService.PROCESS_SUFFIX) }?.pid
}
