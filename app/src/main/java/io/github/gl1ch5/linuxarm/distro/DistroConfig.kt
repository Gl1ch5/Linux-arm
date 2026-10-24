package io.github.gl1ch5.linuxarm.distro

import io.github.gl1ch5.linuxarm.catalog.ImageEntry
import kotlinx.serialization.Serializable

/** Per-distro settings, stored as distros/<id>/config.json. */
@Serializable
data class DistroConfig(
    /** "auto" = newest installed driver for this libc, "none" = software rendering, or a driver id. */
    val driverId: String = DRIVER_AUTO,
    /** The selection before the last change, for the "roll back" button. */
    val previousDriverId: String? = null,
    /** zink (OpenGL over Turnip), freedreno (Gallium on KGSL, needs a driver built with it) or llvmpipe. */
    val glDriver: String = "zink",
    /** Run vulkaninfo before the session and fall back to llvmpipe if Turnip fails. */
    val turnipPreflight: Boolean = true,
    /** Preferred display refresh rate in Hz, 0 = highest the panel supports. */
    val refreshRate: Int = 0,
    /** Bind /storage/emulated/0 into the guest at /sdcard. */
    val shareStorage: Boolean = false,
    /** Extra environment variables, one KEY=VALUE per line, applied last. */
    val extraEnv: String = "",
    /** Replaces the image's launch command when not blank. */
    val customLaunchCommand: String = "",
) {
    companion object {
        const val DRIVER_AUTO = "auto"
        const val DRIVER_NONE = "none"
    }
}

/** What is stored next to an installed rootfs: the catalog entry it came from. */
@Serializable
data class InstalledImage(
    val image: ImageEntry,
    val installedAt: Long,
)
