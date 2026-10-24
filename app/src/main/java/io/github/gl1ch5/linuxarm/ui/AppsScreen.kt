package io.github.gl1ch5.linuxarm.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.gl1ch5.linuxarm.App
import io.github.gl1ch5.linuxarm.apps.DesktopApp
import io.github.gl1ch5.linuxarm.apps.DesktopApps
import io.github.gl1ch5.linuxarm.apps.ShortcutActivity
import io.github.gl1ch5.linuxarm.runtime.SessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Programs of one distro: launch them or pin them to the Android home screen. */
@Composable
fun AppsScreen(nav: NavController, id: String, snackbar: SnackbarHostState) {
    val app = App.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val inst = app.distros.installed.value[id]
    var apps by remember { mutableStateOf<List<DesktopApp>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(id) { apps = withContext(Dispatchers.IO) { DesktopApps.scan(app.paths.rootfs(id)) } }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Программы: ${inst?.image?.name ?: id}", nav)
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Поиск") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Text(
            "Тап — запустить. Кнопка справа — ярлык на рабочий стол Android: он сам запустит дистрибутив и программу.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp, 8.dp),
        )
        val list = apps
        if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        val filtered = list?.filter { query.isBlank() || it.name.contains(query, true) || it.comment.contains(query, true) }
            ?: emptyList()
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(filtered, key = { it.id }) { a ->
                ListItem(
                    leadingContent = { AppIcon(a) },
                    headlineContent = { Text(a.name) },
                    supportingContent = {
                        Text(a.comment.ifBlank { a.exec }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    trailingContent = {
                        IconButton(onClick = {
                            val ok = ShortcutActivity.pin(ctx, id, inst?.image?.name ?: id, a)
                            if (!ok) scope.launch { snackbar.showSnackbar("Лаунчер не поддерживает ярлыки") }
                        }) { Icon(Icons.Filled.AddToHomeScreen, "Ярлык на рабочий стол") }
                    },
                    modifier = Modifier.clickable {
                        SessionService.send(ctx, SessionService.ACTION_LAUNCH_APP, id, DesktopApps.command(a))
                    },
                )
            }
        }
    }
}

@Composable
private fun AppIcon(a: DesktopApp) {
    val bmp = remember(a.iconFile) { a.iconFile?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() } }
    Box(Modifier.size(44.dp)) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize())
        else Icon(Icons.Filled.Apps, null, Modifier.fillMaxSize().padding(6.dp))
    }
}
