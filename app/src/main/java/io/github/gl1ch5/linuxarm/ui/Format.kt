package io.github.gl1ch5.linuxarm.ui

import java.util.Locale

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f КБ", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.0f МБ", bytes / 1048576.0)
    else -> String.format(Locale.US, "%.1f ГБ", bytes / 1073741824.0)
}
