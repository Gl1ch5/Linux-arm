package io.github.gl1ch5.linuxarm.core

import android.content.Context
import java.io.File

/** All on-disk locations used by the app, in one place. */
class AppPaths(context: Context) {
    val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
    val apk = File(context.applicationInfo.sourceDir)

    val files: File = context.filesDir
    val distros = File(files, "distros")
    val drivers = File(files, "drivers")
    val logs = File(files, "logs")
    val config = File(files, "config")
    val prootTmp = File(context.cacheDir, "proot-tmp")
    val downloads = File(context.cacheDir, "downloads")
    val catalogCache = File(files, "catalog")

    val proot = File(nativeLibDir, "libproot.so")
    val prootLoader = File(nativeLibDir, "libproot-loader.so")

    fun distroDir(id: String) = File(distros, id)
    fun rootfs(id: String) = File(distroDir(id), "rootfs")
    fun distroConfig(id: String) = File(distroDir(id), "config.json")
    fun fakeProc(id: String) = File(distroDir(id), "proc")
    fun driverDir(id: String) = File(drivers, id)

    fun ensure() {
        listOf(distros, drivers, logs, config, prootTmp, downloads, catalogCache).forEach { it.mkdirs() }
    }
}
