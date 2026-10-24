package io.github.gl1ch5.linuxarm.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import io.github.gl1ch5.linuxarm.App
import io.github.gl1ch5.linuxarm.catalog.ImageEntry
import io.github.gl1ch5.linuxarm.core.PhantomProcesses
import io.github.gl1ch5.linuxarm.distro.DistroStatus
import io.github.gl1ch5.linuxarm.runtime.SessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController, snackbar: SnackbarHostState) {
    val app = App.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog by app.catalog.images.collectAsState()
    val installed by app.distros.installed.collectAsState()
    val status by app.distros.status.collectAsState()
    var phantom by remember { mutableStateOf<PhantomProcesses.Status?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { phantom = withContext(Dispatchers.IO) { PhantomProcesses.status(ctx) } }
    }
    LaunchedEffect(Unit) {
        refreshing = true
        app.catalog.refresh().onFailure { if (catalog.isEmpty()) snackbar.showSnackbar("Не удалось загрузить каталог: ${it.message}") }
        refreshing = false
    }

    // Installed images that vanished from the catalog stay visible.
    val entries = catalog + installed.values.map { it.image }.filter { i -> catalog.none { it.id == i.id } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Linux ARM", fontWeight = FontWeight.SemiBold) },
            actions = {
                IconButton(enabled = !refreshing, onClick = {
                    scope.launch {
                        refreshing = true
                        app.catalog.refresh().onFailure { snackbar.showSnackbar("Каталог: ${it.message}") }
                        refreshing = false
                    }
                }) { Icon(Icons.Filled.Refresh, "Обновить каталог") }
            },
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(340.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (phantom == PhantomProcesses.Status.ENABLED) {
                item(span = { GridItemSpan(maxLineSpan) }) { PhantomWarning() }
            }
            if (refreshing && entries.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
            items(entries, key = { it.id }) { image ->
                DistroTile(
                    image = image,
                    status = status[image.id] ?: DistroStatus.NotInstalled,
                    installedVersion = installed[image.id]?.image?.version,
                    onPrimary = { st ->
                        when (st) {
                            DistroStatus.NotInstalled, is DistroStatus.Failed ->
                                if (installed.containsKey(image.id)) SessionService.send(ctx, SessionService.ACTION_START, image.id)
                                else SessionService.send(ctx, SessionService.ACTION_INSTALL, image.id)
                            DistroStatus.Installed -> SessionService.send(ctx, SessionService.ACTION_START, image.id)
                            DistroStatus.Running, DistroStatus.Starting -> app.sessions.openDisplay()
                            is DistroStatus.Downloading, is DistroStatus.Extracting ->
                                SessionService.send(ctx, SessionService.ACTION_CANCEL_INSTALL, image.id)
                        }
                    },
                    onStop = { SessionService.send(ctx, SessionService.ACTION_STOP) },
                    onSettings = { nav.navigate("distro/${image.id}") },
                    onDiagnostics = { nav.navigate("diagnostics/${image.id}") },
                    onReinstall = { SessionService.send(ctx, SessionService.ACTION_INSTALL, image.id) },
                    onDelete = { scope.launch(Dispatchers.IO) { app.distros.uninstall(image.id) } },
                )
            }
            if (!refreshing && entries.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Каталог пуст. Проверьте интернет и нажмите «Обновить».", Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun PhantomWarning() {
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null)
                Spacer(Modifier.width(8.dp))
                Text("Android может убивать процессы Linux", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Ограничение дочерних процессов включено. Рабочий стол запускает десятки процессов, и система " +
                    "будет закрывать их («phantom process killer»). Включите «Параметры разработчика» " +
                    "(7 раз нажмите «Номер сборки» в разделе «Об устройстве»), затем в них — " +
                    "«Отключить ограничения дочерних процессов».",
                style = MaterialTheme.typography.bodyMedium,
            )
            FilledTonalButton(onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                    .onFailure { ctx.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
            }) { Text("Открыть параметры разработчика") }
        }
    }
}

private fun accentOf(image: ImageEntry): Color =
    runCatching { Color(android.graphics.Color.parseColor(image.accent ?: "#3D5AFE")) }.getOrDefault(Color(0xFF3D5AFE))

@Composable
private fun DistroTile(
    image: ImageEntry,
    status: DistroStatus,
    installedVersion: String?,
    onPrimary: (DistroStatus) -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    onDiagnostics: () -> Unit,
    onReinstall: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val isInstalled = installedVersion != null
    val accent = accentOf(image)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable { onPrimary(status) },
        shape = RoundedCornerShape(24.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.35f), Color(0xFF101318))))
        ) {
            if (image.previewUrl != null) {
                AsyncImage(
                    model = image.previewUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    image.name.take(1), color = Color.White.copy(alpha = 0.85f), fontSize = 96.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center),
                )
            }
            StatusBadge(status, Modifier.align(Alignment.TopStart).padding(12.dp))
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, "Меню", tint = Color.White)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (isInstalled) {
                        DropdownMenuItem(text = { Text("Запустить") }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                            onClick = { menu = false; onPrimary(DistroStatus.Installed) })
                        if (status == DistroStatus.Running) DropdownMenuItem(text = { Text("Остановить") },
                            leadingIcon = { Icon(Icons.Filled.Stop, null) }, onClick = { menu = false; onStop() })
                        DropdownMenuItem(text = { Text("Настройки") }, leadingIcon = { Icon(Icons.Filled.Settings, null) },
                            onClick = { menu = false; onSettings() })
                        DropdownMenuItem(text = { Text("Проверить GPU") }, leadingIcon = { Icon(Icons.Filled.Speed, null) },
                            onClick = { menu = false; onDiagnostics() })
                        DropdownMenuItem(text = { Text("Переустановить") }, leadingIcon = { Icon(Icons.Filled.Download, null) },
                            onClick = { menu = false; onReinstall() })
                        DropdownMenuItem(text = { Text("Удалить") }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; confirmDelete = true })
                    } else {
                        DropdownMenuItem(text = { Text("Установить") }, leadingIcon = { Icon(Icons.Filled.Download, null) },
                            onClick = { menu = false; onPrimary(DistroStatus.NotInstalled) })
                    }
                }
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(image.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (image.description.isNotBlank()) {
                Text(image.description, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(formatSize(if (isInstalled) image.installedSize else image.archiveSize)) })
                AssistChip(onClick = {}, label = { Text(if (image.sessionType == "x11") "X11" else "Wayland") })
                AssistChip(onClick = {}, label = { Text(installedVersion ?: image.version) })
            }
            when (status) {
                is DistroStatus.Downloading -> {
                    LinearProgressIndicator(progress = { status.fraction }, modifier = Modifier.fillMaxWidth())
                    Text("Загрузка ${formatSize(status.done)} из ${formatSize(status.total)}", style = MaterialTheme.typography.bodySmall)
                }
                is DistroStatus.Extracting -> {
                    LinearProgressIndicator(progress = { status.fraction }, modifier = Modifier.fillMaxWidth())
                    Text("Распаковка ${(status.fraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                is DistroStatus.Failed -> Text("Ошибка: ${status.message}", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                DistroStatus.Starting -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> {}
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val (label, icon) = when (status) {
                    DistroStatus.NotInstalled -> "Установить" to Icons.Filled.Download
                    is DistroStatus.Failed -> (if (isInstalled) "Запустить" else "Повторить") to Icons.Filled.Refresh
                    DistroStatus.Installed -> "Запустить" to Icons.Filled.PlayArrow
                    DistroStatus.Starting -> "Запуск…" to Icons.Filled.Monitor
                    DistroStatus.Running -> "Открыть" to Icons.Filled.Monitor
                    is DistroStatus.Downloading, is DistroStatus.Extracting -> "Отмена" to Icons.Filled.Stop
                }
                Button(onClick = { onPrimary(status) }, modifier = Modifier.weight(1f).height(52.dp)) {
                    Icon(icon, null); Spacer(Modifier.width(8.dp)); Text(label)
                }
                if (status == DistroStatus.Running) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.height(52.dp)) { Text("Стоп") }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить ${image.name}?") },
            text = { Text("Все файлы внутри дистрибутива будут удалены без возможности восстановления.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun StatusBadge(status: DistroStatus, modifier: Modifier) {
    val (text, color) = when (status) {
        DistroStatus.NotInstalled -> "Не установлен" to Color(0x99000000)
        is DistroStatus.Downloading -> "Загрузка" to Color(0xCC1565C0)
        is DistroStatus.Extracting -> "Распаковка" to Color(0xCC1565C0)
        DistroStatus.Installed -> "Установлен" to Color(0xCC2E7D32)
        DistroStatus.Starting -> "Запуск" to Color(0xCCEF6C00)
        DistroStatus.Running -> "Запущен" to Color(0xCC00897B)
        is DistroStatus.Failed -> "Ошибка" to Color(0xCCC62828)
    }
    Text(
        text, color = Color.White, style = MaterialTheme.typography.labelLarge,
        modifier = modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
