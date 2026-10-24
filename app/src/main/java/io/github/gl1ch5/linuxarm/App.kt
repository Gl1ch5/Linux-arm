package io.github.gl1ch5.linuxarm

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Display
import com.termux.x11.LorieApp
import io.github.gl1ch5.linuxarm.catalog.CatalogRepository
import io.github.gl1ch5.linuxarm.core.AppLog
import io.github.gl1ch5.linuxarm.core.AppPaths
import io.github.gl1ch5.linuxarm.distro.DistroRepository
import io.github.gl1ch5.linuxarm.driver.DriverManager
import io.github.gl1ch5.linuxarm.runtime.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Extends Termux:X11's application class, which the embedded display activity relies on. */
class App : LorieApp() {
    lateinit var paths: AppPaths
    lateinit var catalog: CatalogRepository
    lateinit var distros: DistroRepository
    lateinit var drivers: DriverManager
    lateinit var sessions: SessionManager
    lateinit var controls: io.github.gl1ch5.linuxarm.controls.ControlsRepository
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        paths = AppPaths(this).also { it.ensure() }
        AppLog.init(paths.logs)
        if (getProcessName().endsWith(io.github.gl1ch5.linuxarm.x11.XServerService.PROCESS_SUFFIX)) {
            // The X server process needs nothing but logging.
            return
        }
        AppLog.i("app start: ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_COMMIT}), process ${android.os.Process.myPid()}")
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(Thread.getDefaultUncaughtExceptionHandler()))
        catalog = CatalogRepository(this, paths).also { it.loadCached() }
        distros = DistroRepository(paths).also { it.load() }
        drivers = DriverManager(paths).also { it.load() }
        sessions = SessionManager(this, paths, distros, drivers)
        controls = io.github.gl1ch5.linuxarm.controls.ControlsRepository(java.io.File(paths.config, "controls.json"))
        registerActivityLifecycleCallbacks(RefreshRateApplier())
    }

    /** Applies the running distro's refresh rate preference to the X display activity. */
    private inner class RefreshRateApplier : ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity !is io.github.gl1ch5.linuxarm.display.DisplayActivity) return
            val id = sessions.running.value?.distroId ?: return
            val want = distros.config(id).refreshRate
            val display: Display = activity.display ?: return
            val current = display.mode
            val candidates = display.supportedModes.filter {
                it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
            }
            val mode = if (want <= 0) candidates.maxByOrNull { it.refreshRate }
            else candidates.minByOrNull { kotlin.math.abs(it.refreshRate - want) }
            if (mode != null) {
                val attrs = activity.window.attributes
                if (attrs.preferredDisplayModeId != mode.modeId) {
                    attrs.preferredDisplayModeId = mode.modeId
                    activity.window.attributes = attrs
                    AppLog.i("display mode ${mode.modeId}: ${mode.physicalWidth}x${mode.physicalHeight}@${mode.refreshRate} (wanted ${if (want <= 0) "max" else want})")
                }
            }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    private class CrashLogger(val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            AppLog.e("CRASH in thread ${t.name}", e)
            next?.uncaughtException(t, e)
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

val Application.app get() = this as App
