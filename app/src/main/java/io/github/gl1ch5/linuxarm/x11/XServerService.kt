package io.github.gl1ch5.linuxarm.x11

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.system.Os
import android.system.OsConstants
import io.github.gl1ch5.linuxarm.core.AppLog
import java.io.File

/**
 * Hosts the Termux:X11 server (libXlorie) in its own process ":xserver".
 *
 * Termux:X11 normally runs CmdEntryPoint through `app_process`; here the same class is
 * constructed inside an ordinary app process instead, which needs neither app_process
 * nor hidden framework APIs. An X server cannot be restarted inside one process, so
 * stopping the service kills the process.
 */
class XServerService : Service() {
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLog.i("xserver: stop requested")
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        val tmp = intent?.getStringExtra(EXTRA_TMPDIR) ?: return START_NOT_STICKY
        val xkb = intent.getStringExtra(EXTRA_XKB)
        val args = intent.getStringArrayExtra(EXTRA_ARGS) ?: arrayOf(":0")
        started = true
        try {
            redirectStdio(File(filesDir, "logs/xserver.log"))
            Os.setenv("TMPDIR", tmp, true)
            if (xkb != null) Os.setenv("XKB_CONFIG_ROOT", xkb, true)
            AppLog.i("xserver: starting in pid ${Process.myPid()} with ${args.joinToString(" ")} TMPDIR=$tmp XKB=$xkb")
            System.loadLibrary("Xlorie")
            com.termux.x11.CmdEntryPoint.ctx = applicationContext
            // Package-private constructor: starts the server thread, exits the process on failure.
            val ctor = com.termux.x11.CmdEntryPoint::class.java.getDeclaredConstructor(Array<String>::class.java)
            ctor.isAccessible = true
            ctor.newInstance(args as Any)
            AppLog.i("xserver: started")
        } catch (t: Throwable) {
            AppLog.e("xserver: failed to start", t)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Sends the server's stdout/stderr (ErrorF, xkbcomp) to a log file. */
    private fun redirectStdio(log: File) {
        runCatching {
            log.parentFile?.mkdirs()
            if (log.exists()) log.renameTo(File(log.path + ".1"))
            val fd = Os.open(log.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_APPEND, 0x180)
            Os.dup2(fd, 1)
            Os.dup2(fd, 2)
            Os.close(fd)
        }.onFailure { AppLog.w("xserver: cannot redirect stdio", it) }
    }

    override fun onDestroy() {
        AppLog.i("xserver: service destroyed, killing process ${Process.myPid()}")
        super.onDestroy()
        Process.killProcess(Process.myPid())
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_TMPDIR = "tmpdir"
        const val EXTRA_XKB = "xkb"
        const val EXTRA_ARGS = "args"
        const val PROCESS_SUFFIX = ":xserver"
    }
}
