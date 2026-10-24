package io.github.gl1ch5.linuxarm.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.gl1ch5.linuxarm.App
import io.github.gl1ch5.linuxarm.catalog.DriverRelease
import io.github.gl1ch5.linuxarm.core.AppLog
import io.github.gl1ch5.linuxarm.driver.InstalledDriver
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriversScreen(snackbar: SnackbarHostState) {
    val app = App.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val remote by app.drivers.remote.collectAsState()
    val installed by app.drivers.installed.collectAsState()
    val progress by app.drivers.progress.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    fun refresh() = scope.launch {
        loading = true
        app.drivers.refreshRemote().onFailure { snackbar.showSnackbar("Список драйверов: ${it.message}") }
        loading = false
    }
    LaunchedEffect(Unit) { refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "driver.zip"
        scope.launch {
            runCatching { app.drivers.import(name) { ctx.contentResolver.openInputStream(uri)!! } }
                .onSuccess { snackbar.showSnackbar("Импортирован: ${it.name} (${it.libc})") }
                .onFailure { AppLog.e("import failed", it); importError = it.message ?: it.toString() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Драйверы GPU") },
            actions = {
                IconButton(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                    Icon(Icons.Filled.FileOpen, "Импорт")
                }
                IconButton(enabled = !loading, onClick = { refresh() }) { Icon(Icons.Filled.Refresh, "Обновить") }
            },
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "Turnip (Mesa) собирается в GitHub Actions каждый день под KGSL: stable — последний тег Mesa, " +
                        "nightly — ветка main. Можно держать несколько версий и выбирать драйвер для каждого дистрибутива.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { SectionHeader("Установленные (${installed.size})") }
            if (installed.isEmpty()) item { Text("Пока ничего не установлено.") }
            items(installed, key = { it.id }) { d -> InstalledDriverCard(d) { scope.launch { app.drivers.delete(d.id) } } }
            item { SectionHeader("Доступные для загрузки") }
            if (remote.isEmpty() && !loading) item { Text("Список пуст или недоступен.") }
            items(remote, key = { it.id }) { r ->
                RemoteDriverCard(r, installed, progress) { libc ->
                    scope.launch {
                        runCatching { app.drivers.install(r, libc) }
                            .onSuccess { snackbar.showSnackbar("Установлен ${it.name} ($libc)") }
                            .onFailure { AppLog.e("driver install failed", it); snackbar.showSnackbar("Ошибка: ${it.message}") }
                    }
                }
            }
        }
    }

    importError?.let { msg ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Не удалось импортировать") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun SectionHeader(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))

@Composable
private fun InstalledDriverCard(d: InstalledDriver, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(d.name, fontWeight = FontWeight.SemiBold)
                Text("${d.libc} · ${d.channel} · ${d.date.take(10)}" + (if (d.mesaCommit.isNotEmpty()) " · ${d.mesaCommit.take(10)}" else ""),
                    style = MaterialTheme.typography.bodySmall)
                if (!d.hasMesaGl) Text("Только Vulkan: OpenGL (Zink) берётся из Mesa дистрибутива",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, "Удалить") }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Удалить ${d.name}?") },
        text = { Text("Дистрибутивы, выбравшие этот драйвер, перейдут на «Авто».") },
        confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
    )
}

@Composable
private fun RemoteDriverCard(
    r: DriverRelease, installed: List<InstalledDriver>, progress: Map<String, Float>, onInstall: (String) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SuggestionChip(onClick = {}, label = { Text(r.channel) })
                Text("Mesa ${r.version}", fontWeight = FontWeight.SemiBold)
            }
            Text("${r.date.take(10)} · коммит ${r.mesaCommit.take(10)}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.files.forEach { f ->
                    val key = "${r.id}-${f.libc}"
                    val p = progress[key]
                    val done = installed.any { it.id == key }
                    when {
                        p != null -> Column(Modifier.weight(1f)) {
                            Text(f.libc, style = MaterialTheme.typography.labelMedium)
                            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        }
                        done -> TextButton(onClick = {}, enabled = false) { Text("${f.libc}: установлен") }
                        else -> FilledTonalButton(onClick = { onInstall(f.libc) }) { Text("${f.libc} · ${formatSize(f.size)}") }
                    }
                }
            }
        }
    }
}
