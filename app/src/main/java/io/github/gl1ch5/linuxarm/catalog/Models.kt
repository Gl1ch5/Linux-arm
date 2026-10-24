package io.github.gl1ch5.linuxarm.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val JsonFormat = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
}

/** images.json published by the build-images workflow. */
@Serializable
data class ImagesIndex(
    val schema: Int = 1,
    val generated: String? = null,
    val images: List<ImageEntry> = emptyList(),
)

@Serializable
data class ImageEntry(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "",
    /** glibc or musl: which Turnip build fits this userland. */
    val libc: String = "glibc",
    val url: String,
    val sha256: String,
    @SerialName("archive_size") val archiveSize: Long = 0,
    @SerialName("installed_size") val installedSize: Long = 0,
    /** x11 or wayland-nested */
    @SerialName("session_type") val sessionType: String = "x11",
    /** Shell command, run as the desktop session inside the guest. */
    @SerialName("launch_command") val launchCommand: String,
    @SerialName("preview_url") val previewUrl: String? = null,
    /** Accent colour (#RRGGBB) for the tile when there is no preview. */
    val accent: String? = null,
    val user: String = "root",
)

/** drivers.json published by the build-turnip workflow (turnip-index release). */
@Serializable
data class DriversIndex(
    val schema: Int = 1,
    val generated: String? = null,
    val drivers: List<DriverRelease> = emptyList(),
)

/** manifest.json of one driver release. */
@Serializable
data class DriverRelease(
    val id: String,
    val channel: String,
    val version: String,
    @SerialName("mesa_commit") val mesaCommit: String = "",
    val date: String = "",
    val files: List<DriverFile> = emptyList(),
)

@Serializable
data class DriverFile(
    val libc: String,
    val name: String,
    val url: String,
    val sha256: String,
    val size: Long = 0,
)
