package io.github.gl1ch5.linuxarm.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.gl1ch5.linuxarm.App
import io.github.gl1ch5.linuxarm.core.AppLog
import io.github.gl1ch5.linuxarm.core.DeviceInfo
import kotlinx.coroutines.launch

private data class CheckResult(val title: String, val code: Int, val output: String)

/** "Проверить GPU": vulkaninfo --summary and glxinfo -B inside the distro. */
@Composable
fun DiagnosticsScreen(nav: NavController, id: String) {
    val app = App.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by app.sessions.running.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<CheckResult>>(emptyList()) }
    val device = remember { DeviceInfo.summary(ctx) }

    fun runChecks() = scope.launch {
        busy = true
        val out = mutableListOf<CheckResult>()
        AppLog.rotate("diagnostics.log")
        AppLog.appendTo("diagnostics.log", device)
        val (vc, vo) = app.sessions.runInGuest(id, listOf("vulkaninfo", "--summary"), display = null, timeoutSec = 40)
        out += CheckResult("vulkaninfo --summary", vc, vo)
        results = out.toList()
        val display = if (running?.distroId == id) ":0" else null
        if (display != null) {
            val (gc, go) = app.sessions.runInGuest(id, listOf("glxinfo", "-B"), display = display, timeoutSec = 40)
            out += CheckResult("glxinfo -B", gc, go)
        } else {
            out += CheckResult("glxinfo -B", -1, "Пропущено: glxinfo нужен работающий X-сервер. Запустите дистрибутив и повторите проверку.")
        }
        out.forEach { AppLog.appendTo("diagnostics.log", "### ${it.title} (exit ${it.code})\n${it.output}") }
        results = out
        busy = false
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar("Проверка GPU", nav) {
            IconButton(onClick = { scope.launch { shareLogs(ctx) } }) { Icon(Icons.Filled.Share, "Поделиться логами") }
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { runChecks() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(if (busy) "Проверяю…" else "Проверить GPU")
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            results.forEach { r -> ResultCard(r) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Устройство", fontWeight = FontWeight.SemiBold)
                    Text(device, style = MonoStyle)
                }
            }
        }
    }
}

@Composable
private fun ResultCard(r: CheckResult) {
    val ok = r.code == 0
    val turnip = r.output.contains("turnip", true) || r.output.contains("Adreno", true)
    val llvmpipe = r.output.contains("llvmpipe", true)
    val verdict = when {
        !ok -> "Ошибка (код ${r.code})"
        turnip -> "Аппаратное ускорение: Turnip / Adreno"
        llvmpipe -> "Программный рендер (llvmpipe)"
        else -> "Готово"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(r.title, fontWeight = FontWeight.SemiBold)
            Text(verdict, color = if (ok && turnip) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            SelectionContainer {
                Text(r.output.trim(), style = MonoStyle, modifier = Modifier.horizontalScroll(rememberScrollState()))
            }
        }
    }
}
