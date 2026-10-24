package io.github.gl1ch5.linuxarm.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Picks an action string (keys / command / app action / nothing). */
@Composable
fun ActionPicker(action: String, onChange: (String) -> Unit) {
    val type = when {
        action.startsWith("keys:") -> 0
        action.startsWith("cmd:") -> 1
        action.startsWith("app:") -> 2
        else -> 3
    }
    var keys by remember { mutableStateOf(if (type == 0) action.removePrefix("keys:") else "") }
    var cmd by remember { mutableStateOf(if (type == 1) action.removePrefix("cmd:") else "") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val labels = listOf("Клавиши", "Команда", "Приложение", "Нет")
            labels.forEachIndexed { i, l ->
                SegmentedButton(
                    selected = type == i,
                    onClick = {
                        onChange(when (i) {
                            0 -> "keys:" + keys.ifBlank { "SUPER" }
                            1 -> "cmd:" + cmd.ifBlank { "xterm" }
                            2 -> "app:" + AppAction.MENU.id
                            else -> Actions.NONE
                        })
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                ) { Text(l, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            }
        }
        when (type) {
            0 -> {
                OutlinedTextField(
                    value = keys, onValueChange = { keys = it; onChange("keys:$it") },
                    label = { Text("Сочетание, например CTRL+ALT+T") }, singleLine = true,
                    isError = Actions.parseKeys(keys) == null, modifier = Modifier.fillMaxWidth(),
                )
                Text("CTRL ALT SHIFT SUPER ESC TAB ENTER UP DOWN LEFT RIGHT F1…F12 A…Z 0…9",
                    style = MaterialTheme.typography.bodySmall)
            }
            1 -> OutlinedTextField(
                value = cmd, onValueChange = { cmd = it; onChange("cmd:$it") },
                label = { Text("Команда внутри дистрибутива") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            2 -> Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                AppAction.entries.forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = action == "app:${a.id}") { onChange("app:${a.id}") },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = action == "app:${a.id}", onClick = null)
                        Text(a.label)
                    }
                }
            }
        }
    }
}

@Composable
fun ActionDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var action by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { ActionPicker(action) { action = it } },
        confirmButton = { TextButton(onClick = { onSave(action) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
