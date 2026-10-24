package io.github.gl1ch5.linuxarm.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.gl1ch5.linuxarm.App
import io.github.gl1ch5.linuxarm.BuildConfig
import io.github.gl1ch5.linuxarm.distro.DistroConfig

@Composable
fun DistroSettingsScreen(nav: NavController, id: String) {
    val app = App.instance
    val ctx = LocalContext.current
    val configs by app.distros.configs.collectAsState()
    val installedDrivers by app.drivers.installed.collectAsState()
    val inst = app.distros.installed.collectAsState().value[id]
    val cfg = configs[id] ?: app.distros.config(id)
    fun save(c: DistroConfig) = app.distros.saveConfig(id, c)
    val libc = inst?.image?.libc ?: "glibc"

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Настройки: ${inst?.image?.name ?: id}", nav)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SectionTitle("Драйвер GPU (Vulkan)")
            val options = buildList {
                add(DistroConfig.DRIVER_AUTO to "Авто — самый свежий установленный ($libc)")
                installedDrivers.filter { it.libc == libc }.forEach { add(it.id to it.name) }
                add(DistroConfig.DRIVER_NONE to "Без GPU — программный рендер (llvmpipe)")
            }
            val subtitles = installedDrivers.associate { it.id to "${it.date.take(10)} · ${it.mesaCommit.take(10)} · ${it.source}" }
            RadioGroup(options, cfg.driverId, subtitles) { sel ->
                if (sel != cfg.driverId) save(cfg.copy(driverId = sel, previousDriverId = cfg.driverId))
            }
            installedDrivers.filter { it.libc != libc }.takeIf { it.isNotEmpty() }?.let {
                Text("Скрыто ${it.size} драйв. под другую libc.", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            cfg.previousDriverId?.let { prev ->
                OutlinedButton(
                    onClick = { save(cfg.copy(driverId = prev, previousDriverId = cfg.driverId)) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null)
                    Text("  Откатить на: ${app.drivers.get(prev)?.name ?: prev}")
                }
            }

            SectionTitle("OpenGL")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                val gl = listOf("zink" to "Zink", "freedreno" to "Freedreno", "llvmpipe" to "llvmpipe")
                gl.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = cfg.glDriver == value,
                        onClick = { save(cfg.copy(glDriver = value)) },
                        shape = SegmentedButtonDefaults.itemShape(i, gl.size),
                    ) { Text(label) }
                }
            }
            Text(
                "Zink — OpenGL поверх Turnip (рекомендуется). Freedreno — Gallium-драйвер напрямую через KGSL, " +
                    "работает только со сборками, где он включён.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp, 8.dp),
            )
            SwitchRow("Проверять Turnip перед запуском", "vulkaninfo; при ошибке — автоматически llvmpipe",
                cfg.turnipPreflight) { save(cfg.copy(turnipPreflight = it)) }

            SectionTitle("Экран")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                val rates = listOf(0 to "Макс.", 60 to "60 Гц", 90 to "90 Гц", 120 to "120 Гц")
                rates.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = cfg.refreshRate == value,
                        onClick = { save(cfg.copy(refreshRate = value)) },
                        shape = SegmentedButtonDefaults.itemShape(i, rates.size),
                    ) { Text(label) }
                }
            }
            FilledTonalButton(
                onClick = { ctx.startActivity(Intent().setClassName(ctx, "com.termux.x11.LoriePreferences")) },
                modifier = Modifier.padding(16.dp).fillMaxWidth().height(52.dp),
            ) { Text("Разрешение, масштаб, тач и клавиатура (X11)") }

            SectionTitle("Файлы")
            val hasStorage = Environment.isExternalStorageManager()
            SwitchRow("Общая папка /sdcard", if (hasStorage) "Память телефона доступна в /sdcard"
            else "Нужно разрешение «Доступ ко всем файлам»", cfg.shareStorage) { on ->
                if (on && !Environment.isExternalStorageManager()) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${BuildConfig.APPLICATION_ID}")))
                }
                save(cfg.copy(shareStorage = on))
            }

            SectionTitle("Расширенные")
            var env by remember(id) { mutableStateOf(cfg.extraEnv) }
            OutlinedTextField(
                value = env, onValueChange = { env = it; save(cfg.copy(extraEnv = it)) },
                label = { Text("Переменные окружения (KEY=VALUE, по строке)") },
                placeholder = { Text("TU_DEBUG=noconform,sysmem\nMESA_VK_WSI_PRESENT_MODE=mailbox") },
                minLines = 3, textStyle = MonoStyle,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            var launch by remember(id) { mutableStateOf(cfg.customLaunchCommand) }
            OutlinedTextField(
                value = launch, onValueChange = { launch = it; save(cfg.copy(customLaunchCommand = it)) },
                label = { Text("Своя команда запуска") },
                placeholder = { Text(inst?.image?.launchCommand ?: "") },
                singleLine = true, textStyle = MonoStyle,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { nav.navigate("diagnostics/$id") }) { Text("Проверить GPU") }
            }
        }
    }
}
