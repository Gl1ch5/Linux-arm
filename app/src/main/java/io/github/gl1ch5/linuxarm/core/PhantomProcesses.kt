package io.github.gl1ch5.linuxarm.core

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Android 12+ kills "phantom" child processes of apps (more than 32 in total, or ones
 * using too much CPU in the background). A Linux desktop easily has more than that.
 * Android 14+ has a developer option "Disable child process restrictions" which
 * sets the feature flag below to false; older versions need adb.
 */
object PhantomProcesses {
    enum class Status { NOT_APPLICABLE, DISABLED, ENABLED, UNKNOWN }

    private const val FLAG = "settings_enable_monitor_phantom_procs"

    fun status(context: Context): Status {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Status.NOT_APPLICABLE
        val prop = Processes.run(listOf("/system/bin/getprop", "persist.sys.fflag.override.$FLAG"), timeoutSec = 5)
            .second.trim()
        val global = runCatching { Settings.Global.getString(context.contentResolver, FLAG) }.getOrNull()?.trim()
        AppLog.d("phantom check: getprop='$prop' global='$global'")
        return when {
            prop.equals("false", true) || global.equals("false", true) -> Status.DISABLED
            prop.equals("true", true) || global.equals("true", true) -> Status.ENABLED
            // Unset means the default, i.e. the restriction is active.
            else -> Status.ENABLED
        }
    }
}
