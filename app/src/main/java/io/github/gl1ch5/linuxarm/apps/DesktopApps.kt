package io.github.gl1ch5.linuxarm.apps

import java.io.File
import java.util.Locale

/** A launchable program of a distro, read from its .desktop file. */
data class DesktopApp(
    val id: String,
    val name: String,
    val comment: String,
    val exec: String,
    val terminal: Boolean,
    /** Icon file inside the rootfs (PNG/XPM-free raster), or null. */
    val iconFile: File?,
    val categories: List<String>,
)

object DesktopApps {
    private val dirs = listOf("usr/share/applications", "usr/local/share/applications", "root/.local/share/applications")
    private val iconSizes = listOf("256x256", "192x192", "128x128", "96x96", "72x72", "64x64", "48x48")

    fun scan(rootfs: File): List<DesktopApp> {
        val lang = Locale.getDefault().language
        val seen = HashMap<String, DesktopApp>()
        for (d in dirs) {
            File(rootfs, d).listFiles { f -> f.name.endsWith(".desktop") }?.forEach { f ->
                parse(rootfs, f, lang)?.let { seen[f.name] = it }
            }
        }
        return seen.values.sortedBy { it.name.lowercase() }
    }

    private fun parse(rootfs: File, file: File, lang: String): DesktopApp? {
        val entries = HashMap<String, String>()
        var inMain = false
        runCatching { file.readLines() }.getOrNull()?.forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("[") -> inMain = line == "[Desktop Entry]"
                inMain && '=' in line -> entries.putIfAbsent(line.substringBefore('=').trim(), line.substringAfter('=').trim())
            }
        } ?: return null
        if (entries["Type"] != "Application") return null
        if (entries["NoDisplay"] == "true" || entries["Hidden"] == "true") return null
        val exec = entries["Exec"]?.let { cleanExec(it) }?.takeIf { it.isNotBlank() } ?: return null
        val name = entries["Name[$lang]"] ?: entries["Name"] ?: return null
        return DesktopApp(
            id = file.name.removeSuffix(".desktop"),
            name = name,
            comment = entries["Comment[$lang]"] ?: entries["Comment"] ?: entries["GenericName"] ?: "",
            exec = exec,
            terminal = entries["Terminal"] == "true",
            iconFile = entries["Icon"]?.let { findIcon(rootfs, it) },
            categories = entries["Categories"]?.split(';')?.filter { it.isNotBlank() } ?: emptyList(),
        )
    }

    /** Drops desktop-entry field codes (%f, %U, …) that need a file argument. */
    private fun cleanExec(exec: String) =
        exec.replace(Regex("%[fFuUdDnNickvm]"), "").replace("%%", "%").replace(Regex("\\s+"), " ").trim()

    fun findIcon(rootfs: File, icon: String): File? {
        if (icon.startsWith("/")) return File(rootfs, icon.removePrefix("/")).takeIf { it.isFile && it.name.endsWith(".png") }
        val themes = listOf("hicolor", "Adwaita", "elementary-xfce", "Papirus")
        for (theme in themes) for (size in iconSizes) {
            for (cat in listOf("apps", "categories", "places", "devices")) {
                val f = File(rootfs, "usr/share/icons/$theme/$size/$cat/$icon.png")
                if (f.isFile) return f
            }
        }
        return File(rootfs, "usr/share/pixmaps/$icon.png").takeIf { it.isFile }
    }

    /** Command line to run [app], wrapped in a terminal when the entry asks for one. */
    fun command(app: DesktopApp): String =
        if (app.terminal) "x-terminal-emulator -e ${app.exec} || xfce4-terminal -e '${app.exec}' || kgx -- ${app.exec}"
        else app.exec
}
