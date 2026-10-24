package io.github.gl1ch5.linuxarm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.gl1ch5.linuxarm.App
import io.github.gl1ch5.linuxarm.controls.ActionDialog
import io.github.gl1ch5.linuxarm.controls.Actions
import io.github.gl1ch5.linuxarm.controls.Gesture

/** Global settings of the X display: fullscreen, gestures, on-screen buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlsScreen() {
    val repo = App.instance.controls
    val c by repo.config.collectAsState()
    var editGesture by remember { mutableStateOf<Gesture?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Управление") },
            actions = { IconButton(onClick = { confirmReset = true }) { Icon(Icons.Filled.RestartAlt, "Сбросить") } },
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SectionTitle("Экран")
            SwitchRow("Полноэкранный режим", "Скрыть статус-бар и навигацию (свайп от края покажет их)", c.fullscreen) { v ->
                repo.update { it.copy(fullscreen = v) }
            }
            SwitchRow("Использовать область выреза камеры", null, c.hideCutout) { v -> repo.update { it.copy(hideCutout = v) } }
            SwitchRow("Не гасить экран", "Пока открыт рабочий стол", c.keepScreenOn) { v -> repo.update { it.copy(keepScreenOn = v) } }

            SectionTitle("Жесты")
            Text(
                "Три или четыре пальца на экране. Два пальца по-прежнему прокручивают и масштабируют, " +
                    "долгое нажатие — правый клик (в режиме тачпада).",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp),
            )
            Gesture.entries.forEach { g ->
                ListItem(
                    headlineContent = { Text(g.label) },
                    supportingContent = { Text(Actions.describe(c.gesture(g))) },
                    modifier = Modifier.clickable { editGesture = g },
                )
            }

            SectionTitle("Кнопки на экране")
            SwitchRow("Показывать кнопки", "Расставить и настроить кнопки: меню экрана → «Настроить кнопки»", c.showButtons) { v ->
                repo.update { it.copy(showButtons = v) }
            }
            Text("Прозрачность: ${(c.buttonOpacity * 100).toInt()}%", Modifier.padding(horizontal = 16.dp))
            Slider(
                value = c.buttonOpacity, onValueChange = { v -> repo.update { it.copy(buttonOpacity = v) } },
                valueRange = 0.1f..0.9f, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Text("Размер: ${c.buttonSizeDp} dp", Modifier.padding(horizontal = 16.dp))
            Slider(
                value = c.buttonSizeDp.toFloat(), onValueChange = { v -> repo.update { it.copy(buttonSizeDp = v.toInt()) } },
                valueRange = 36f..88f, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            c.buttons.forEach { b ->
                ListItem(headlineContent = { Text(b.label) }, supportingContent = { Text(Actions.describe(b.action)) })
            }
        }
    }

    editGesture?.let { g ->
        ActionDialog(g.label, c.gesture(g), onDismiss = { editGesture = null }) { a ->
            repo.update { it.copy(gestures = it.gestures + (g.id to a)) }
            editGesture = null
        }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Сбросить управление?") },
        text = { Text("Жесты, кнопки и настройки экрана вернутся к стандартным.") },
        confirmButton = { TextButton(onClick = { repo.reset(); confirmReset = false }) { Text("Сбросить") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } },
    )
}
