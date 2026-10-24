package io.github.gl1ch5.linuxarm.controls

import android.view.KeyEvent
import io.github.gl1ch5.linuxarm.catalog.JsonFormat
import io.github.gl1ch5.linuxarm.core.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * What a gesture or an on-screen button does, as a string:
 *  - "keys:CTRL+ALT+T"  key combination sent to the X server
 *  - "cmd:xfce4-terminal" shell command run inside the running distro
 *  - "app:<name>"       an app action, see [AppAction]
 *  - "none"
 */
object Actions {
    const val NONE = "none"

    fun describe(action: String): String = when {
        action.startsWith("keys:") -> action.removePrefix("keys:")
        action.startsWith("cmd:") -> "$ " + action.removePrefix("cmd:")
        action.startsWith("app:") -> AppAction.entries.firstOrNull { it.id == action.removePrefix("app:") }?.label ?: action
        else -> "Ничего"
    }

    /** Parses "CTRL+ALT+T" into Android key codes, or null if a name is unknown. */
    fun parseKeys(combo: String): List<Int>? = combo.split('+').map { it.trim() }.filter { it.isNotEmpty() }
        .map { keyCode(it) ?: return null }.takeIf { it.isNotEmpty() }

    private val aliases = mapOf(
        "CTRL" to KeyEvent.KEYCODE_CTRL_LEFT, "CONTROL" to KeyEvent.KEYCODE_CTRL_LEFT,
        "ALT" to KeyEvent.KEYCODE_ALT_LEFT, "ALTGR" to KeyEvent.KEYCODE_ALT_RIGHT,
        "SHIFT" to KeyEvent.KEYCODE_SHIFT_LEFT,
        "SUPER" to KeyEvent.KEYCODE_META_LEFT, "META" to KeyEvent.KEYCODE_META_LEFT, "WIN" to KeyEvent.KEYCODE_META_LEFT,
        "ESC" to KeyEvent.KEYCODE_ESCAPE, "ESCAPE" to KeyEvent.KEYCODE_ESCAPE,
        "TAB" to KeyEvent.KEYCODE_TAB, "ENTER" to KeyEvent.KEYCODE_ENTER, "RETURN" to KeyEvent.KEYCODE_ENTER,
        "BACKSPACE" to KeyEvent.KEYCODE_DEL, "DEL" to KeyEvent.KEYCODE_FORWARD_DEL, "DELETE" to KeyEvent.KEYCODE_FORWARD_DEL,
        "INS" to KeyEvent.KEYCODE_INSERT, "INSERT" to KeyEvent.KEYCODE_INSERT,
        "UP" to KeyEvent.KEYCODE_DPAD_UP, "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
        "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT, "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "HOME" to KeyEvent.KEYCODE_MOVE_HOME, "END" to KeyEvent.KEYCODE_MOVE_END,
        "PGUP" to KeyEvent.KEYCODE_PAGE_UP, "PGDN" to KeyEvent.KEYCODE_PAGE_DOWN,
        "SPACE" to KeyEvent.KEYCODE_SPACE, "PRINT" to KeyEvent.KEYCODE_SYSRQ,
    )

    private fun keyCode(name: String): Int? {
        val n = name.uppercase()
        aliases[n]?.let { return it }
        val code = KeyEvent.keyCodeFromString("KEYCODE_$n")
        return code.takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
    }
}

enum class AppAction(val id: String, val label: String) {
    MENU("menu", "Меню экрана"),
    KEYBOARD("keyboard", "Клавиатура"),
    EXTRA_KEYS("extrakeys", "Строка доп. клавиш"),
    TOUCH_MODE("touchmode", "Тачпад ⇄ прямое касание"),
    BUTTONS("buttons", "Показать/скрыть кнопки"),
    RIGHT_CLICK("rightclick", "Правый клик"),
    MIDDLE_CLICK("middleclick", "Средний клик"),
    HOME("home", "Вернуться в приложение"),
    STOP("stop", "Остановить сессию"),
}

@Serializable
data class OverlayButton(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val action: String,
    /** Position of the button's centre as a fraction of the screen (0..1). */
    val x: Float,
    val y: Float,
)

/** Gestures recognised on top of the X display (fingers + direction). */
enum class Gesture(val id: String, val label: String) {
    TAP3("3tap", "Тап тремя пальцами"),
    UP3("3up", "Три пальца вверх"),
    DOWN3("3down", "Три пальца вниз"),
    LEFT3("3left", "Три пальца влево"),
    RIGHT3("3right", "Три пальца вправо"),
    TAP4("4tap", "Тап четырьмя пальцами"),
    UP4("4up", "Четыре пальца вверх"),
    DOWN4("4down", "Четыре пальца вниз"),
}

@Serializable
data class ControlsConfig(
    val showButtons: Boolean = true,
    /** 0..1 */
    val buttonOpacity: Float = 0.45f,
    val buttonSizeDp: Int = 52,
    val buttons: List<OverlayButton> = defaultButtons(),
    val gestures: Map<String, String> = defaultGestures(),
    /** Hide status and navigation bars (swipe from the edge shows them briefly). */
    val fullscreen: Boolean = true,
    /** Use the area around the camera cutout too. */
    val hideCutout: Boolean = true,
    /** Keep the device awake while the display is visible. */
    val keepScreenOn: Boolean = true,
    /** One-time switch of Termux:X11 defaults (hidden extra keys bar) on first start. */
    val termuxDefaultsApplied: Boolean = false,
) {
    fun gesture(g: Gesture): String = gestures[g.id] ?: Actions.NONE

    companion object {
        fun defaultButtons() = listOf(
            OverlayButton(label = "☰", action = "app:menu", x = 0.95f, y = 0.10f),
            OverlayButton(label = "⌨", action = "app:keyboard", x = 0.95f, y = 0.24f),
            OverlayButton(label = "Esc", action = "keys:ESC", x = 0.05f, y = 0.10f),
            OverlayButton(label = "Super", action = "keys:SUPER", x = 0.05f, y = 0.24f),
        )

        fun defaultGestures() = mapOf(
            Gesture.TAP3.id to "app:keyboard",
            Gesture.UP3.id to "app:menu",
            Gesture.DOWN3.id to "app:buttons",
            Gesture.LEFT3.id to "keys:ALT+SHIFT+TAB",
            Gesture.RIGHT3.id to "keys:ALT+TAB",
            Gesture.TAP4.id to "app:touchmode",
            Gesture.UP4.id to "keys:SUPER",
            Gesture.DOWN4.id to "app:home",
        )
    }
}

/** Global controls configuration stored in files/config/controls.json. */
class ControlsRepository(private val file: File) {
    private val _config = MutableStateFlow(load())
    val config: StateFlow<ControlsConfig> = _config

    private fun load(): ControlsConfig =
        runCatching { JsonFormat.decodeFromString<ControlsConfig>(file.readText()) }.getOrElse { ControlsConfig() }

    fun update(transform: (ControlsConfig) -> ControlsConfig) {
        val c = transform(_config.value)
        _config.value = c
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(JsonFormat.encodeToString(ControlsConfig.serializer(), c))
        }.onFailure { AppLog.w("cannot save controls", it) }
    }

    fun reset() = update { ControlsConfig() }
}
