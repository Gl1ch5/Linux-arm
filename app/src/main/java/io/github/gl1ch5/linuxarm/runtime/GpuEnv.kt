package io.github.gl1ch5.linuxarm.runtime

import io.github.gl1ch5.linuxarm.distro.DistroConfig
import io.github.gl1ch5.linuxarm.driver.DriverManager.Companion.GUEST_MOUNT
import io.github.gl1ch5.linuxarm.driver.InstalledDriver
import java.io.File

/** Environment variables that select the GPU stack inside a guest. */
object GpuEnv {
    /** Turnip on KGSL, OpenGL through Zink (or freedreno on KGSL when asked for). */
    fun turnip(driver: InstalledDriver, driverDir: File, cfg: DistroConfig): Map<String, String> {
        val env = linkedMapOf<String, String>()
        env["VK_ICD_FILENAMES"] = "$GUEST_MOUNT/${driver.icd}"
        env["TU_DEBUG"] = "noconform"
        if (driver.hasMesaGl) {
            // Our builds carry a complete Mesa (GLX/EGL/GBM + Zink) under /opt/gpu-driver/usr.
            env["LD_LIBRARY_PATH"] = "$GUEST_MOUNT/usr/lib"
            env["LIBGL_DRIVERS_PATH"] = "$GUEST_MOUNT/usr/lib/dri"
            if (File(driverDir, "usr/share/glvnd/egl_vendor.d/50_mesa.json").exists()) {
                env["__EGL_VENDOR_LIBRARY_FILENAMES"] = "$GUEST_MOUNT/usr/share/glvnd/egl_vendor.d/50_mesa.json"
            }
            if (File(driverDir, "usr/lib/gbm").isDirectory) env["GBM_BACKENDS_PATH"] = "$GUEST_MOUNT/usr/lib/gbm"
            env["__GLX_VENDOR_LIBRARY_NAME"] = "mesa"
        }
        when (cfg.glDriver) {
            "freedreno" -> env["MESA_LOADER_DRIVER_OVERRIDE"] = "kgsl"
            "llvmpipe" -> env.putAll(software())
            else -> {
                env["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
                env["GALLIUM_DRIVER"] = "zink"
                // Termux:X11 has DRI3 but not DRI3 1.2 + Present 1.2 ("multibuffer"). Without
                // that, Mesa's GLX refuses an explicitly requested zink ("DRI3 not available")
                // and every GLX client, gnome-shell included, gets no context at all. Zink's
                // kopper presents through Turnip's Vulkan swapchain, which works here.
                env["LIBGL_KOPPER_DRI2"] = "1"
            }
        }
        return env
    }

    fun software(): Map<String, String> = mapOf(
        "LIBGL_ALWAYS_SOFTWARE" to "1",
        "GALLIUM_DRIVER" to "llvmpipe",
    )

    /** Parses KEY=VALUE lines; blank lines and # comments are ignored. */
    fun parseExtra(text: String): Map<String, String> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
}
