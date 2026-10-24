package io.github.gl1ch5.linuxarm.display

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardAlt
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gl1ch5.linuxarm.App
import io.github.gl1ch5.linuxarm.controls.ActionPicker
import io.github.gl1ch5.linuxarm.controls.Actions
import io.github.gl1ch5.linuxarm.controls.AppAction
import io.github.gl1ch5.linuxarm.controls.ControlsConfig
import io.github.gl1ch5.linuxarm.controls.OverlayButton
import io.github.gl1ch5.linuxarm.ui.AppTheme
import kotlin.math.roundToInt

@Composable
fun DisplayOverlay(activity: DisplayActivity) {
    val app = activity.application as App
    val config by app.controls.config.collectAsState()
    var menuOpen by activity.menuOpen
    var editing by activity.editingButtons

    AppTheme {
        Box(Modifier.fillMaxSize()) {
            if (editing) {
                ButtonEditor(config, onDone = { buttons ->
                    app.controls.update { it.copy(buttons = buttons, showButtons = true) }
                    editing = false
                })
            } else if (config.showButtons) {
                OverlayButtons(config) { activity.perform(it.action) }
            }
            if (menuOpen && !editing) {
                DisplayMenu(
                    activity = activity, config = config,
                    onClose = { menuOpen = false },
                    onEdit = { menuOpen = false; editing = true },
                )
            }
        }
    }
}

/** Translucent buttons at their saved positions. Empty space stays touch-transparent. */
@Composable
private fun OverlayButtons(config: ControlsConfig, onPress: (OverlayButton) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val sizePx = with(LocalDensity.current) { config.buttonSizeDp.dp.roundToPx() }
        config.buttons.forEach { b ->
            OverlayKey(
                b.label, config.buttonSizeDp, config.buttonOpacity,
                Modifier.offset { IntOffset((b.x * w - sizePx / 2f).roundToInt(), (b.y * h - sizePx / 2f).roundToInt()) }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPress(b) },
            )
        }
    }
}

@Composable
private fun OverlayKey(label: String, sizeDp: Int, opacity: Float, modifier: Modifier, highlighted: Boolean = false) {
    Box(
        modifier
            .size(sizeDp.dp)
            .background(Color.Black.copy(alpha = opacity), RoundedCornerShape(sizeDp.dp / 3))
            .border(1.dp, if (highlighted) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = opacity * 0.8f),
                RoundedCornerShape(sizeDp.dp / 3)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White.copy(alpha = (opacity + 0.4f).coerceAtMost(1f)),
            fontSize = (sizeDp / 3.2f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Drag buttons around, tap one to change it, add new ones. */
@Composable
private fun ButtonEditor(config: ControlsConfig, onDone: (List<OverlayButton>) -> Unit) {
    val buttons = remember { mutableStateListOf<OverlayButton>().apply { addAll(config.buttons) } }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val sizePx = with(LocalDensity.current) { config.buttonSizeDp.dp.toPx() }
        buttons.forEachIndexed { i, b ->
            OverlayKey(
                b.label, config.buttonSizeDp, maxOf(config.buttonOpacity, 0.6f),
                Modifier
                    .offset { IntOffset((b.x * w - sizePx / 2).roundToInt(), (b.y * h - sizePx / 2).roundToInt()) }
                    .pointerInput(b.id) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val idx = buttons.indexOfFirst { it.id == b.id }
                            if (idx < 0) return@detectDragGestures
                            val cur = buttons[idx]
                            buttons[idx] = cur.copy(
                                x = (cur.x + drag.x / w).coerceIn(0.02f, 0.98f),
                                y = (cur.y + drag.y / h).coerceIn(0.02f, 0.98f),
                            )
                        }
                    }
                    .pointerInput(b.id + "tap") { detectTapGestures { editIndex = buttons.indexOfFirst { it.id == b.id } } },
                highlighted = true,
            )
        }
        Surface(
            shape = RoundedCornerShape(50), tonalElevation = 6.dp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
        ) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    buttons += OverlayButton(label = "Btn", action = "keys:ENTER", x = 0.5f, y = 0.5f)
                    editIndex = buttons.lastIndex
                }) { Icon(Icons.Filled.Add, null); Text(" Добавить") }
                TextButton(onClick = { buttons.clear(); buttons.addAll(ControlsConfig.defaultButtons()) }) {
                    Icon(Icons.Filled.RestartAlt, null)
                }
                TextButton(onClick = { onDone(buttons.toList()) }) { Icon(Icons.Filled.Check, null); Text(" Готово") }
            }
        }
        Text(
            "Перетаскивайте кнопки. Тап — изменить.",
            color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
        )
    }
    editIndex?.let { i ->
        val b = buttons.getOrNull(i) ?: return@let
        var label by remember(i) { mutableStateOf(b.label) }
        var action by remember(i) { mutableStateOf(b.action) }
        AlertDialog(
            onDismissRequest = { editIndex = null },
            title = { Text("Кнопка") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(label, { label = it.take(8) }, label = { Text("Надпись") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    ActionPicker(action) { action = it }
                }
            },
            confirmButton = {
                TextButton(onClick = { buttons[i] = b.copy(label = label.ifBlank { "?" }, action = action); editIndex = null }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { buttons.removeAt(i); editIndex = null }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

/** Panel opened by the menu button or a gesture. */
@Composable
private fun DisplayMenu(activity: DisplayActivity, config: ControlsConfig, onClose: () -> Unit, onEdit: () -> Unit) {
    val app = activity.application as App
    val running by app.sessions.running.collectAsState()
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            tonalElevation = 8.dp,
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(running?.let { "Сессия: ${app.distros.installed.value[it.distroId]?.image?.name ?: it.distroId}" } ?: "Нет сессии",
                    style = MaterialTheme.typography.titleMedium)
                running?.let { Text("GPU: ${it.gpu}", style = MaterialTheme.typography.bodySmall) }
                val touchpad = activity.prefs.touchMode.get() == "1"
                fun act(a: AppAction) { onClose(); activity.perform("app:${a.id}") }
                MenuRow(
                    MenuItem(Icons.Filled.Keyboard, "Клавиатура") { act(AppAction.KEYBOARD) },
                    MenuItem(Icons.Filled.KeyboardAlt, "Доп. клавиши") { act(AppAction.EXTRA_KEYS) },
                    MenuItem(if (touchpad) Icons.Filled.Mouse else Icons.Filled.TouchApp,
                        if (touchpad) "Тачпад" else "Касание") { act(AppAction.TOUCH_MODE) },
                )
                MenuRow(
                    MenuItem(Icons.Filled.Visibility, if (config.showButtons) "Скрыть кнопки" else "Показать кнопки") { act(AppAction.BUTTONS) },
                    MenuItem(Icons.Filled.Edit, "Настроить кнопки") { onEdit() },
                    MenuItem(Icons.Filled.Settings, "Экран X11") {
                        onClose()
                        activity.startActivity(Intent().setClassName(activity, "com.termux.x11.LoriePreferences")
                            .setAction(Intent.ACTION_MAIN))
                    },
                )
                MenuRow(
                    MenuItem(Icons.AutoMirrored.Filled.ExitToApp, "В приложение") { onClose(); activity.goHome() },
                    MenuItem(Icons.Filled.Stop, "Остановить") { act(AppAction.STOP) },
                )
                Text(
                    "Жесты: " + listOf("3tap", "3up", "3down").joinToString(" · ") { id ->
                        val g = io.github.gl1ch5.linuxarm.controls.Gesture.entries.first { it.id == id }
                        "${g.label.lowercase()} — ${Actions.describe(config.gesture(g)).lowercase()}"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class MenuItem(val icon: ImageVector, val label: String, val onClick: () -> Unit)

@Composable
private fun MenuRow(vararg items: MenuItem) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { item ->
            FilledTonalButton(onClick = item.onClick, modifier = Modifier.weight(1f).height(72.dp),
                shape = RoundedCornerShape(20.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(item.icon, null)
                    Text(item.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}
