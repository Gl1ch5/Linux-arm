package io.github.gl1ch5.linuxarm.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Android 12+ kills "phantom" child processes of apps (more than 32 in total, or ones
 * using too much CPU in the background). A Linux desktop easily has more than that.
 * Android 14+ has a developer option "Disable child process restrictions" which
 * sets the feature flag below to false; older versions need adb.
 *
 * Without the developer option the app still protects itself: [ProcessGuard] keeps the
 * process count under the limit, the session service is a foreground service (so the
 * "excessive CPU in the background" check does not apply) and the app asks to be exempt
 * from battery optimization, which OEM task killers (ColorOS, MIUI, One UI) respect.
 * If WRITE_SECURE_SETTINGS was granted once (adb or Shizuku), the app turns the
 * restriction off by itself on every start.
 */
object PhantomProcesses {
    enum class Status { NOT_APPLICABLE, DISABLED, ENABLED, UNKNOWN }

    private const val FLAG = "settings_enable_monitor_phantom_procs"

    const val GRANT_COMMAND = "adb shell pm grant io.github.gl1ch5.linuxarm android.permission.WRITE_SECURE_SETTINGS"

    fun canWriteSecureSettings(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Turns the restriction off through Settings.Global, which takes precedence over the
     * property the developer option sets and is picked up by ActivityManager at once.
     * Returns true when the setting was written.
     */
    fun disableIfPermitted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !canWriteSecureSettings(context)) return false
        return runCatching {
            val cr = context.contentResolver
            if (Settings.Global.getString(cr, FLAG) != "false") {
                Settings.Global.putString(cr, FLAG, "false")
                AppLog.i("phantom process restriction disabled via WRITE_SECURE_SETTINGS")
            }
            true
        }.onFailure { AppLog.w("cannot write $FLAG", it) }.getOrDefault(false)
    }

    fun ignoresBatteryOptimizations(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** The system dialog "Allow the app to always run in the background?". */
    @SuppressLint("BatteryLife")
    fun batteryExemptionIntent(context: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** App info page, where OEM skins keep their own "background activity" switches. */
    fun appDetailsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun status(context: Context): Status {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return Status.NOT_APPLICABLE
        disableIfPermitted(context)
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
