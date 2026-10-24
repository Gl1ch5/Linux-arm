package io.github.gl1ch5.linuxarm.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.system.Os
import io.github.gl1ch5.linuxarm.BuildConfig
import java.io.File

object DeviceInfo {
    const val KGSL_DEVICE = "/dev/kgsl-3d0"

    fun kgslAccessible(): Boolean = File(KGSL_DEVICE).let { it.exists() && it.canRead() && it.canWrite() }

    /** Human readable GPU name from KGSL sysfs, when readable (often it is not). */
    fun gpuModel(): String? = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_model",
        "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_model",
    ).firstNotNullOfOrNull { p -> runCatching { File(p).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } }

    fun pageSize(): Long = runCatching { Os.sysconf(android.system.OsConstants._SC_PAGESIZE) }.getOrDefault(-1)

    fun summary(context: Context): String = buildString {
        appendLine("App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) commit ${BuildConfig.GIT_COMMIT}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), SoC ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}")
        appendLine("Kernel: ${runCatching { Os.uname().let { "${it.release} ${it.version}" } }.getOrDefault("?")}")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}, page size ${pageSize()}")
        appendLine("GPU: ${gpuModel() ?: "unknown"}, $KGSL_DEVICE accessible=${kgslAccessible()}")
        val phantom = PhantomProcesses.status(context)
        appendLine("Phantom process restriction: $phantom")
        val rt = Runtime.getRuntime()
        appendLine("Memory: max heap ${rt.maxMemory() / 1048576} MB")
        appendLine("Animator scale: ${Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)}")
    }
}
