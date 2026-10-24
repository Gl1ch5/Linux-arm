package io.github.gl1ch5.linuxarm.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.gl1ch5.linuxarm.App
import io.github.gl1ch5.linuxarm.BuildConfig
import io.github.gl1ch5.linuxarm.update.UpdateChannel
import io.github.gl1ch5.linuxarm.update.UpdateState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen() {
    val u = App.instance.updates
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by u.latest.collectAsState()
    val lastCheck by u.lastCheck.collectAsState()
    val error by u.checkError.collectAsState()
    val state by u.state.collectAsState()
    val channel by u.channel.collectAsState()
    val auto by u.autoCheck.collectAsState()
    var canInstall = u.canInstallPackages()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canInstall = u.canInstallPackages() }
    LaunchedEffect(Unit) { u.check() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Обновления") },
            actions = { IconButton(onClick = { scope.launch { u.check() } }) { Icon(Icons.Filled.Refresh, "Проверить") } },
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Установлено", style = MaterialTheme.typography.labelLarge)
                    Text("${BuildConfig.VERSION_NAME} (сборка ${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Коммит ${BuildConfig.GIT_COMMIT}", style = MaterialTheme.typography.bodySmall)
                }
            }

            val v = latest
            val newer = u.isNewer(v)
            Card(
                Modifier.fillMaxWidth(),
                colors = if (newer) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                else CardDefaults.cardColors(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        v == null -> Text(error?.let { "Не удалось проверить: $it" } ?: "Проверяю…")
                        newer -> {
                            Text("Доступна новая версия", style = MaterialTheme.typography.labelLarge)
                            Text("${v.versionName} (сборка ${v.versionCode})", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                            Text("${v.date.take(16).replace('T', ' ')} UTC · ${v.branch} · ${v.commit} · ${formatSize(v.size)}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        else -> Text("У вас последняя версия (${v.versionName})")
                    }
                    if (v != null && newer) {
                        when (val st = state) {
                            is UpdateState.Downloading -> {
                                LinearProgressIndicator(progress = { st.fraction }, modifier = Modifier.fillMaxWidth())
                                Text("Загрузка ${(st.fraction * 100).toInt()}%")
                            }
                            UpdateState.Installing -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Установка…") }
                            is UpdateState.Failed -> Text("Ошибка: ${st.message}", color = MaterialTheme.colorScheme.error)
                            UpdateState.Idle -> {}
                        }
                        if (!canInstall) {
                            Text("Разрешите приложению устанавливать обновления (один раз).",
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { u.openInstallPermissionSettings() }) { Text("Открыть разрешение") }
                        }
                        Button(
                            onClick = { u.install(v) },
                            enabled = canInstall && state !is UpdateState.Downloading && state !is UpdateState.Installing,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) { Text("Скачать и установить") }
                    }
                    if (lastCheck > 0) Text("Последняя проверка: ${DateFormat.getTimeInstance().format(Date(lastCheck))}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (v != null && v.changes.isNotEmpty()) {
                SectionTitle("Последние изменения")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        v.changes.forEach { line ->
                            Text("• " + line.substringAfter(' '), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            SectionTitle("Канал")
            RadioGroup(
                UpdateChannel.entries.map { it to it.label }, channel,
                UpdateChannel.entries.associateWith { it.description },
            ) { u.setChannel(it) }
            SwitchRow("Проверять каждую минуту", "Пока приложение запущено; при новой версии — уведомление", auto) {
                u.setAutoCheck(it)
            }
            OutlinedButton(
                onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/${BuildConfig.GITHUB_REPO}/releases".toUri())) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Все версии на GitHub") }
        }
    }
}
