package io.github.gl1ch5.linuxarm.driver

import io.github.gl1ch5.linuxarm.BuildConfig
import io.github.gl1ch5.linuxarm.catalog.DriverFile
import io.github.gl1ch5.linuxarm.catalog.DriverRelease
import io.github.gl1ch5.linuxarm.catalog.DriversIndex
import io.github.gl1ch5.linuxarm.catalog.JsonFormat
import io.github.gl1ch5.linuxarm.core.AppLog
import io.github.gl1ch5.linuxarm.core.AppPaths
import io.github.gl1ch5.linuxarm.distro.DistroConfig
import io.github.gl1ch5.linuxarm.distro.RootfsExtractor
import io.github.gl1ch5.linuxarm.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** A driver unpacked under filesDir/drivers/<id>, mounted into guests at /opt/gpu-driver. */
@Serializable
data class InstalledDriver(
    val id: String,
    val name: String,
    val channel: String,
    val version: String,
    val libc: String,
    /** "ci" for our own builds, "import" for user supplied archives. */
    val source: String,
    val mesaCommit: String = "",
    val date: String = "",
    val installedAt: Long = 0,
    /** ICD json path inside the mount point. */
    val icd: String,
    /** True when the archive has a full Mesa (GL/EGL, Zink); imports usually only have Vulkan. */
    val hasMesaGl: Boolean = false,
)

class DriverManager(private val paths: AppPaths) {
    companion object {
        const val GUEST_MOUNT = "/opt/gpu-driver"
    }

    private val _installed = MutableStateFlow<List<InstalledDriver>>(emptyList())
    val installed: StateFlow<List<InstalledDriver>> = _installed

    private val _remote = MutableStateFlow<List<DriverRelease>>(emptyList())
    val remote: StateFlow<List<DriverRelease>> = _remote

    /** Download progress per "<release>-<libc>" key, 0..1. */
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress

    val indexUrl = "https://github.com/${BuildConfig.GITHUB_REPO}/releases/download/turnip-index/drivers.json"
    private val remoteCache get() = File(paths.catalogCache, "drivers.json")

    fun load() {
        _installed.value = paths.drivers.listFiles()?.mapNotNull { dir ->
            runCatching { JsonFormat.decodeFromString<InstalledDriver>(File(dir, "driver.json").readText()) }.getOrNull()
        }?.sortedByDescending { it.installedAt } ?: emptyList()
        runCatching { parseIndex(remoteCache.readText()) }.getOrNull()?.let { _remote.value = it }
    }

    suspend fun refreshRemote(): Result<List<DriverRelease>> = runCatching {
        val text = Http.getText(indexUrl)
        val list = parseIndex(text)
        remoteCache.writeText(text)
        _remote.value = list
        AppLog.i("drivers index: ${list.size} releases")
        list
    }.onFailure { AppLog.w("drivers index refresh failed: $it") }

    private fun parseIndex(text: String) =
        JsonFormat.decodeFromString<DriversIndex>(text).drivers.sortedByDescending { it.date }

    fun get(id: String) = _installed.value.firstOrNull { it.id == id }

    /** The driver a distro should use, or null for software rendering. */
    fun resolve(cfg: DistroConfig, libc: String): InstalledDriver? = when (cfg.driverId) {
        DistroConfig.DRIVER_NONE -> null
        DistroConfig.DRIVER_AUTO -> _installed.value.filter { it.libc == libc }
            .maxWithOrNull(compareBy<InstalledDriver> { it.source == "ci" }.thenBy { it.date }.thenBy { it.installedAt })
        // A deleted driver falls back to automatic selection.
        else -> get(cfg.driverId) ?: resolve(cfg.copy(driverId = DistroConfig.DRIVER_AUTO), libc)
    }

    /** Releases newer than anything installed for [libc] in the same channel. */
    fun updatesFor(libc: String): List<DriverRelease> = _remote.value.filter { r ->
        r.files.any { it.libc == libc } && _installed.value.none { it.id == installId(r, libc) } &&
            _installed.value.filter { it.channel == r.channel && it.libc == libc }.all { it.date < r.date }
    }

    fun installId(r: DriverRelease, libc: String) = "${r.id}-$libc"

    suspend fun install(release: DriverRelease, libc: String): InstalledDriver = withContext(Dispatchers.IO) {
        val file: DriverFile = release.files.firstOrNull { it.libc == libc }
            ?: error("release ${release.id} has no $libc build")
        val id = installId(release, libc)
        val key = id
        try {
            val archive = File(paths.downloads, file.name)
            Http.download(file.url, archive, file.sha256) { done, total ->
                val t = if (total > 0) total else file.size
                _progress.update { it + (key to if (t > 0) done.toFloat() / t * 0.8f else 0f) }
            }
            val dir = paths.driverDir(id)
            RootfsExtractor.deleteTree(dir)
            RootfsExtractor.extract(archive, dir) { f -> _progress.update { it + (key to 0.8f + f * 0.2f) } }
            archive.delete()
            val icd = findIcd(dir) ?: error("driver archive has no Vulkan ICD json")
            val d = InstalledDriver(
                id = id, name = "Turnip ${release.channel} ${release.version}", channel = release.channel,
                version = release.version, libc = libc, source = "ci", mesaCommit = release.mesaCommit,
                date = release.date, installedAt = System.currentTimeMillis(), icd = icd,
                hasMesaGl = File(dir, "usr/lib/dri").isDirectory || File(dir, "usr/lib").listFiles()
                    ?.any { it.name.startsWith("libgallium") } == true,
            )
            writeMeta(dir, d)
            AppLog.i("driver installed: $d")
            d
        } finally {
            _progress.update { it - key }
        }
    }

    /**
     * Imports a zip in AdrenoTools layout (meta.json + .so) or one of our .tar.zst archives.
     * AdrenoTools packages for Android emulators are linked against bionic and cannot be
     * loaded by a glibc/musl guest; those are rejected with an explanation.
     */
    suspend fun import(displayName: String, open: () -> InputStream): InstalledDriver = withContext(Dispatchers.IO) {
        val id = "import-" + displayName.lowercase().replace(Regex("[^a-z0-9.]+"), "-").trim('-').take(40) +
            "-" + (System.currentTimeMillis() / 1000)
        val dir = paths.driverDir(id)
        RootfsExtractor.deleteTree(dir)
        dir.mkdirs()
        try {
            if (displayName.endsWith(".tar.zst")) {
                val tmp = File(paths.downloads, "$id.tar.zst")
                open().use { i -> tmp.outputStream().use { i.copyTo(it) } }
                RootfsExtractor.extract(tmp, dir) {}
                tmp.delete()
                val manifest = runCatching { JsonFormat.decodeFromString<DriverRelease>(File(dir, "manifest.json").readText()) }.getOrNull()
                val icd = findIcd(dir) ?: error("в архиве нет Vulkan ICD (usr/share/vulkan/icd.d/*.json)")
                val lib = File(dir, "usr/lib/libvulkan_freedreno.so")
                val libc = libcOf(lib)
                val d = InstalledDriver(id, manifest?.let { "Turnip ${it.channel} ${it.version}" } ?: displayName,
                    manifest?.channel ?: "import", manifest?.version ?: "?", libc, "import",
                    manifest?.mesaCommit ?: "", manifest?.date ?: "", System.currentTimeMillis(), icd, true)
                writeMeta(dir, d)
                return@withContext d
            }
            ZipInputStream(open()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = File(e.name).name // flatten, AdrenoTools zips are flat anyway
                    File(dir, "lib").mkdirs()
                    File(dir, "lib/$name").outputStream().use { zip.copyTo(it) }
                }
            }
            val metaFile = File(dir, "lib/meta.json")
            val meta = runCatching { JsonFormat.decodeFromString<AdrenoToolsMeta>(metaFile.readText()) }.getOrNull()
            val libName = meta?.libraryName ?: File(dir, "lib").listFiles()?.firstOrNull { it.name.endsWith(".so") }?.name
                ?: error("в архиве нет .so файла драйвера")
            val lib = File(dir, "lib/$libName")
            val libc = libcOf(lib)
            val icd = File(dir, "icd.json")
            icd.writeText(
                """{"file_format_version":"1.0.1","ICD":{"library_path":"$GUEST_MOUNT/lib/$libName","api_version":"1.4.0"}}"""
            )
            val d = InstalledDriver(
                id = id, name = meta?.name ?: displayName, channel = "import",
                version = meta?.driverVersion ?: meta?.packageVersion ?: "?", libc = libc, source = "import",
                installedAt = System.currentTimeMillis(), icd = "icd.json", hasMesaGl = false,
            )
            writeMeta(dir, d)
            AppLog.i("driver imported: $d")
            d
        } catch (e: Exception) {
            RootfsExtractor.deleteTree(dir)
            throw e
        }
    }

    private fun libcOf(lib: File): String {
        val info = ElfInfo.read(lib) ?: error("${lib.name} не является ELF-библиотекой")
        AppLog.i("import: ${lib.name} machine=${info.machine} needed=${info.needed}")
        if (!info.isAarch64) error("${lib.name} собран не под aarch64")
        return when (info.libc) {
            ElfInfo.Libc.GLIBC -> "glibc"
            ElfInfo.Libc.MUSL -> "musl"
            ElfInfo.Libc.BIONIC -> error(
                "Это сборка драйвера для Android-приложений (bionic: ${info.needed.joinToString()}). " +
                    "Linux-дистрибутивы внутри приложения используют glibc или musl, поэтому такой драйвер не загрузится. " +
                    "Нужна сборка Turnip под Linux aarch64 (KGSL)."
            )
            ElfInfo.Libc.UNKNOWN -> "glibc"
        }
    }

    private fun findIcd(dir: File): String? =
        File(dir, "usr/share/vulkan/icd.d").listFiles { f -> f.name.endsWith(".json") }?.firstOrNull()
            ?.relativeTo(dir)?.path

    private fun writeMeta(dir: File, d: InstalledDriver) {
        File(dir, "driver.json").writeText(JsonFormat.encodeToString(InstalledDriver.serializer(), d))
        _installed.update { list -> (list.filter { it.id != d.id } + d).sortedByDescending { it.installedAt } }
    }

    fun delete(id: String) {
        AppLog.i("driver delete $id")
        RootfsExtractor.deleteTree(paths.driverDir(id))
        _installed.update { list -> list.filter { it.id != id } }
    }
}

@Serializable
data class AdrenoToolsMeta(
    val name: String? = null,
    val description: String? = null,
    val author: String? = null,
    val packageVersion: String? = null,
    val vendor: String? = null,
    val driverVersion: String? = null,
    val libraryName: String? = null,
)
