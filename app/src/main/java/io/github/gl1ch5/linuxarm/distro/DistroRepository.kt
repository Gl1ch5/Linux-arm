package io.github.gl1ch5.linuxarm.distro

import android.system.Os
import io.github.gl1ch5.linuxarm.catalog.ImageEntry
import io.github.gl1ch5.linuxarm.catalog.JsonFormat
import io.github.gl1ch5.linuxarm.core.AppLog
import io.github.gl1ch5.linuxarm.core.AppPaths
import io.github.gl1ch5.linuxarm.net.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

sealed interface DistroStatus {
    data object NotInstalled : DistroStatus
    data class Downloading(val done: Long, val total: Long) : DistroStatus {
        val fraction get() = if (total > 0) done.toFloat() / total else 0f
    }
    data class Extracting(val fraction: Float) : DistroStatus
    data object Installed : DistroStatus
    data object Starting : DistroStatus
    data object Running : DistroStatus
    data class Failed(val message: String) : DistroStatus
}

class DistroRepository(private val paths: AppPaths) {
    private val _status = MutableStateFlow<Map<String, DistroStatus>>(emptyMap())
    val status: StateFlow<Map<String, DistroStatus>> = _status

    private val _installed = MutableStateFlow<Map<String, InstalledImage>>(emptyMap())
    val installed: StateFlow<Map<String, InstalledImage>> = _installed

    private val _configs = MutableStateFlow<Map<String, DistroConfig>>(emptyMap())
    val configs: StateFlow<Map<String, DistroConfig>> = _configs

    fun load() {
        val inst = mutableMapOf<String, InstalledImage>()
        val cfgs = mutableMapOf<String, DistroConfig>()
        paths.distros.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val id = dir.name
            val meta = File(dir, "installed.json")
            if (meta.exists() && paths.rootfs(id).isDirectory) {
                runCatching { JsonFormat.decodeFromString<InstalledImage>(meta.readText()) }
                    .onSuccess { inst[id] = it }
                    .onFailure { AppLog.w("bad installed.json for $id", it) }
            }
            cfgs[id] = readConfig(id)
        }
        _installed.value = inst
        _configs.value = cfgs
        _status.value = inst.keys.associateWith { DistroStatus.Installed }
    }

    fun statusOf(id: String): DistroStatus = _status.value[id] ?: DistroStatus.NotInstalled

    fun setStatus(id: String, s: DistroStatus) = _status.update { it + (id to s) }

    fun config(id: String): DistroConfig = _configs.value[id] ?: readConfig(id)

    fun saveConfig(id: String, cfg: DistroConfig) {
        paths.distroDir(id).mkdirs()
        paths.distroConfig(id).writeText(JsonFormat.encodeToString(DistroConfig.serializer(), cfg))
        _configs.update { it + (id to cfg) }
    }

    private fun readConfig(id: String): DistroConfig =
        runCatching { JsonFormat.decodeFromString<DistroConfig>(paths.distroConfig(id).readText()) }
            .getOrDefault(DistroConfig())

    /** Downloads, verifies and unpacks [image]. Runs in the caller's coroutine. */
    suspend fun install(image: ImageEntry) {
        val id = image.id
        AppLog.i("install ${image.id} ${image.version} from ${image.url}")
        try {
            setStatus(id, DistroStatus.Downloading(0, image.archiveSize))
            val archive = File(paths.downloads, "$id-${image.version}.tar.zst")
            val cached = archive.exists() && Http.sha256(archive).equals(image.sha256, true)
            if (!cached) {
                Http.download(image.url, archive, image.sha256) { done, total ->
                    setStatus(id, DistroStatus.Downloading(done, if (total > 0) total else image.archiveSize))
                }
            }
            setStatus(id, DistroStatus.Extracting(0f))
            val dir = paths.distroDir(id)
            val staging = File(dir, "rootfs.partial")
            RootfsExtractor.deleteTree(staging)
            RootfsExtractor.extract(archive, staging) { setStatus(id, DistroStatus.Extracting(it)) }
            val rootfs = paths.rootfs(id)
            RootfsExtractor.deleteTree(rootfs)
            if (!staging.renameTo(rootfs)) error("cannot move rootfs into place")
            GuestSetup.afterInstall(rootfs)
            val meta = InstalledImage(image, System.currentTimeMillis())
            File(dir, "installed.json").writeText(JsonFormat.encodeToString(InstalledImage.serializer(), meta))
            archive.delete()
            _installed.update { it + (id to meta) }
            if (!paths.distroConfig(id).exists()) saveConfig(id, DistroConfig())
            setStatus(id, DistroStatus.Installed)
            AppLog.i("install ${image.id} done")
        } catch (e: kotlinx.coroutines.CancellationException) {
            AppLog.w("install $id cancelled")
            setStatus(id, if (_installed.value.containsKey(id)) DistroStatus.Installed else DistroStatus.NotInstalled)
            throw e
        } catch (e: Exception) {
            AppLog.e("install $id failed", e)
            setStatus(id, DistroStatus.Failed(e.message ?: e.toString()))
        }
    }

    private val trash get() = File(paths.distros.parentFile, "trash")

    /**
     * Moves the distro out of the way at once (a rename), so that starting or installing
     * it again right away cannot touch half-deleted files, then deletes it.
     */
    fun uninstall(id: String) {
        AppLog.i("uninstall $id")
        _installed.update { it - id }
        _configs.update { it - id }
        setStatus(id, DistroStatus.NotInstalled)
        val dir = paths.distroDir(id)
        trash.mkdirs()
        val moved = File(trash, "$id-${System.nanoTime()}")
        val target = if (dir.renameTo(moved)) moved else dir
        runCatching { RootfsExtractor.deleteTree(target) }
            .onFailure { AppLog.w("could not delete all of $target; it is retried on the next start", it) }
    }

    /** Leftovers of interrupted uninstalls. */
    fun emptyTrash() {
        trash.listFiles()?.forEach { runCatching { RootfsExtractor.deleteTree(it) } }
    }
}

/** Small fixes applied to a freshly unpacked rootfs. */
object GuestSetup {
    fun afterInstall(rootfs: File) {
        File(rootfs, "etc").mkdirs()
        val resolv = File(rootfs, "etc/resolv.conf")
        if (java.nio.file.Files.isSymbolicLink(resolv.toPath())) resolv.delete()
        resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        val hosts = File(rootfs, "etc/hosts")
        if (!hosts.exists() || hosts.length() == 0L) {
            hosts.writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        }
        for (d in listOf("tmp", "root", "dev/shm", "run")) File(rootfs, d).mkdirs()
        runCatching { Os.chmod(File(rootfs, "tmp").path, 0x3ff /* 1777 */) }
    }
}
