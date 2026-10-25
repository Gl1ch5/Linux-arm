package io.github.gl1ch5.linuxarm.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.gl1ch5.linuxarm.BuildConfig
import io.github.gl1ch5.linuxarm.core.AppLog
import io.github.gl1ch5.linuxarm.core.DeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf(AppLog.allFiles()) }
    var selected by remember { mutableStateOf<String?>(AppLog.COMBINED) }
    var text by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(selected, reload) {
        text = withContext(Dispatchers.IO) {
            if (selected == AppLog.COMBINED) AppLog.buildCombined(DeviceInfo.summary(ctx))
            selected?.let { tail(AppLog.file(it), 3000) } ?: "Логов пока нет."
        }
        files = AppLog.allFiles()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Логи") },
            actions = {
                IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, "Обновить") }
                IconButton(onClick = { scope.launch { shareLogs(ctx) } }) { Icon(Icons.Filled.Share, "Поделиться") }
            },
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            files.forEach { f ->
                FilterChip(
                    selected = f.name == selected, onClick = { selected = f.name },
                    label = { Text(if (f.name == AppLog.COMBINED) "Все логи" else f.name) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        val v = rememberScrollState()
        LaunchedEffect(text) { v.scrollTo(v.maxValue) }
        SelectionContainer(Modifier.fillMaxWidth().weight(1f)) {
            Text(
                text, style = MonoStyle,
                modifier = Modifier.verticalScroll(v).horizontalScroll(rememberScrollState()).padding(12.dp),
            )
        }
    }
}

private fun tail(f: File, lines: Int): String {
    if (!f.exists()) return ""
    val all = f.readLines()
    return (if (all.size > lines) listOf("… (${all.size - lines} строк выше, полностью — через «Поделиться»)") else emptyList<String>())
        .plus(all.takeLast(lines)).joinToString("\n")
}

/** Zips all logs plus a device summary and opens the share sheet. */
suspend fun shareLogs(ctx: Context) {
    val zip = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "linux-arm-logs-$stamp.zip")
        val summary = DeviceInfo.summary(ctx)
        AppLog.buildCombined(summary)
        ZipOutputStream(out.outputStream()).use { z ->
            // all.log comes first: it alone holds everything below.
            AppLog.allFiles().filter { it.name == AppLog.COMBINED }.forEach { f ->
                z.putNextEntry(ZipEntry("00-${f.name}"))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            z.putNextEntry(ZipEntry("device.txt"))
            z.write(summary.toByteArray())
            z.closeEntry()
            AppLog.allFiles().filter { it.name != AppLog.COMBINED }.forEach { f ->
                z.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        out
    }
    val uri = FileProvider.getUriForFile(ctx, "${BuildConfig.APPLICATION_ID}.files", zip)
    val send = Intent(Intent.ACTION_SEND)
        .setType("application/zip")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, zip.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Отправить логи").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
