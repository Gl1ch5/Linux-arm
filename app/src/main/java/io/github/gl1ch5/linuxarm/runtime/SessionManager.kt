package io.github.gl1ch5.linuxarm.runtime

import android.content.Context
import android.content.Intent
import io.github.gl1ch5.linuxarm.core.AppLog
import io.github.gl1ch5.linuxarm.core.AppPaths
import io.github.gl1ch5.linuxarm.core.DeviceInfo
import io.github.gl1ch5.linuxarm.core.LoggedProcess
import io.github.gl1ch5.linuxarm.core.Processes
import io.github.gl1ch5.linuxarm.distro.DistroConfig
import io.github.gl1ch5.linuxarm.distro.DistroRepository
import io.github.gl1ch5.linuxarm.distro.DistroStatus
import io.github.gl1ch5.linuxarm.distro.InstalledImage
import io.github.gl1ch5.linuxarm.driver.DriverManager
import io.github.gl1ch5.linuxarm.driver.InstalledDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Starts and stops desktop sessions: X server + proot running the desktop. */
class SessionManager(
    private val context: Context,
    private val paths: AppPaths,
    private val distros: DistroRepository,
    private val drivers: DriverManager,
) {
    data class Running(val distroId: String, val gpu: String)

    private val proot = Proot(paths)
    val xserver = XServer(context)
    private var logcat: LoggedProcess? = null
    private var session: LoggedProcess? = null
    private val lock = Mutex()

    private val _running = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> = _running

    /** User-facing messages (snackbars / notification text). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private fun tell(msg: String) {
        AppLog.i("message: $msg")
        _messages.tryEmit(msg)
    }

    /** Guest environment and extra binds for [id]; [useGpu] = false forces software rendering. */
    data class GuestSetup(val env: Map<String, String>, val binds: List<Proot.Bind>, val driver: InstalledDriver?)

    fun guestSetup(id: String, inst: InstalledImage, cfg: DistroConfig, display: String?, useGpu: Boolean): GuestSetup {
        val env = Proot.baseGuestEnv(display)
        env["LA_DISTRO"] = id
        val binds = mutableListOf<Proot.Bind>()
        val driver = if (useGpu) drivers.resolve(cfg, inst.image.libc) else null
        if (driver != null) {
            binds += Proot.Bind(paths.driverDir(driver.id).path, DriverManager.GUEST_MOUNT)
            env.putAll(GpuEnv.turnip(driver, paths.driverDir(driver.id), cfg))
        } else {
            env.putAll(GpuEnv.software())
        }
        if (cfg.shareStorage) binds += Proot.Bind("/storage/emulated/0", "/sdcard")
        env.putAll(GpuEnv.parseExtra(cfg.extraEnv))
        return GuestSetup(env, binds, driver)
    }

    /** Runs a command in the guest and returns (exit code, output); used for checks and diagnostics. */
    suspend fun runInGuest(
        id: String, argv: List<String>, display: String?, useGpu: Boolean = true, timeoutSec: Long = 30,
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        val inst = distros.installed.value[id] ?: return@withContext -1 to "not installed"
        val setup = guestSetup(id, inst, distros.config(id), display, useGpu)
        val cmd = proot.command(id, setup.env, argv, setup.binds)
        AppLog.d("runInGuest: ${cmd.joinToString(" ") { Processes.quote(it) }}")
        Processes.run(cmd, proot.hostEnv(), timeoutSec)
    }

    suspend fun start(id: String) = lock.withLock {
        withContext(Dispatchers.IO) { startLocked(id) }
    }

    private suspend fun startLocked(id: String) {
        val inst = distros.installed.value[id] ?: run { tell("Дистрибутив $id не установлен"); return }
        _running.value?.let { if (it.distroId == id && session?.isAlive == true) { openDisplay(); return } }
        stopLocked()
        distros.setStatus(id, DistroStatus.Starting)
        AppLog.rotate("session-$id.log")
        AppLog.i("=== starting $id (${inst.image.name} ${inst.image.version}) ===")
        AppLog.i(DeviceInfo.summary(context))
        val cfg = distros.config(id)
        val rootfs = paths.rootfs(id)

        installSessionScript(rootfs)
        startLogcat()

        // GPU: use Turnip if a driver is selected, KGSL is reachable and (optionally) a
        // quick vulkaninfo succeeds; otherwise fall back to llvmpipe and say why.
        var useGpu = cfg.driverId != DistroConfig.DRIVER_NONE
        var gpuLabel = "llvmpipe (программный рендер)"
        if (useGpu) {
            val driver = drivers.resolve(cfg, inst.image.libc)
            when {
                driver == null -> {
                    useGpu = false
                    tell("Драйвер Turnip не установлен (${inst.image.libc}). Запуск с программным рендером. Установите драйвер в разделе «Драйверы».")
                }
                !DeviceInfo.kgslAccessible() -> {
                    useGpu = false
                    tell("Нет доступа к ${DeviceInfo.KGSL_DEVICE}: аппаратное ускорение недоступно, используется llvmpipe.")
                }
                cfg.turnipPreflight -> {
                    val (code, out) = runInGuest(id, listOf("vulkaninfo", "--summary"), display = null, useGpu = true, timeoutSec = 25)
                    AppLog.appendTo("session-$id.log", "--- Turnip preflight (vulkaninfo --summary), exit $code ---\n$out")
                    val ok = code == 0 && (out.contains("turnip", true) || out.contains("freedreno", true))
                    if (ok) {
                        gpuLabel = "Turnip ${driver.version} (${cfg.glDriver})"
                    } else {
                        useGpu = false
                        tell("Turnip не инициализировался (код $code), включён программный рендер llvmpipe. Подробности — в логе session-$id.log.")
                    }
                }
                else -> gpuLabel = "Turnip ${driver.version} (${cfg.glDriver}, без проверки)"
            }
        }
        AppLog.i("GPU for $id: $gpuLabel")

        if (!xserver.start(rootfs)) {
            logcat?.destroy(); logcat = null
            distros.setStatus(id, DistroStatus.Failed("X-сервер не запустился, см. xserver.log"))
            return
        }
        openDisplay()

        val setup = guestSetup(id, inst, cfg, ":0", useGpu)
        val env = setup.env.toMutableMap()
        env["LA_LAUNCH"] = cfg.customLaunchCommand.ifBlank { inst.image.launchCommand }
        val cmd = proot.command(id, env, listOf("/bin/sh", SESSION_SCRIPT), setup.binds)
        val p = Processes.start("session-$id", cmd, proot.hostEnv(), "session-$id.log")
        session = p
        _running.value = Running(id, gpuLabel)
        distros.setStatus(id, DistroStatus.Running)

        Thread {
            val code = p.waitFor()
            AppLog.i("session $id ended with code $code")
            if (session === p) {
                session = null
                xserver.stop()
                logcat?.destroy(); logcat = null
                _running.value = null
                distros.setStatus(id, DistroStatus.Installed)
                if (code != 0 && code != 143 && code != 137) tell("Сессия $id завершилась с кодом $code. Лог: session-$id.log")
            }
        }.apply { isDaemon = true; name = "wait-session" }.start()
    }

    private fun installSessionScript(rootfs: File) {
        val f = File(rootfs, SESSION_SCRIPT.removePrefix("/"))
        f.parentFile?.mkdirs()
        context.assets.open("session.sh").use { i -> f.outputStream().use { i.copyTo(it) } }
        f.setExecutable(true, false)
    }

    fun openDisplay() {
        val i = Intent().setClassName(context, "com.termux.x11.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { context.startActivity(i) }.onFailure { AppLog.e("cannot open display", it) }
    }

    suspend fun stop() = lock.withLock { withContext(Dispatchers.IO) { stopLocked() } }

    /** Captures logcat of this app (X server, display activity) into logcat.log while a session runs. */
    private fun startLogcat() {
        logcat?.destroy()
        AppLog.rotate("logcat.log")
        logcat = runCatching {
            Processes.start("logcat", listOf("/system/bin/logcat", "-v", "threadtime", "-T", "1"),
                mapOf("PATH" to "/system/bin"), "logcat.log")
        }.onFailure { AppLog.w("cannot start logcat", it) }.getOrNull()
    }

    private fun stopLocked() {
        val r = _running.value
        session?.let { AppLog.i("stopping session ${it.name}"); it.destroy() }
        session = null
        xserver.stop()
        logcat?.destroy(); logcat = null
        _running.value = null
        if (r != null) distros.setStatus(r.distroId, DistroStatus.Installed)
        // Close the display activity if it is open.
        runCatching {
            context.sendBroadcast(Intent("com.termux.x11.ACTION_STOP").setPackage(context.packageName))
        }
    }

    companion object {
        const val SESSION_SCRIPT = "/usr/local/lib/linux-arm/session.sh"
    }
}
