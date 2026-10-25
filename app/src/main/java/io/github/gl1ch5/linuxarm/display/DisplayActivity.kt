package io.github.gl1ch5.linuxarm.display

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.termux.x11.input.InputStub
import io.github.gl1ch5.linuxarm.App
import io.github.gl1ch5.linuxarm.controls.Actions
import io.github.gl1ch5.linuxarm.controls.AppAction
import io.github.gl1ch5.linuxarm.controls.Gesture
import io.github.gl1ch5.linuxarm.core.AppLog
import io.github.gl1ch5.linuxarm.runtime.SessionService
import io.github.gl1ch5.linuxarm.ui.MainActivity as LauncherActivity
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The X display, embedded in the app's own task. Extends Termux:X11's activity to keep
 * its rendering and input handling (trackpad / direct touch, two-finger scroll, pinch
 * zoom, extra keys), and adds on top: fullscreen by default, gestures with three and
 * four fingers, and configurable translucent buttons.
 */
class DisplayActivity : com.termux.x11.MainActivity() {
    private val app get() = application as App
    val menuOpen = mutableStateOf(false)
    val editingButtons = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        current = java.lang.ref.WeakReference(this)
        applyControlsToTermuxPrefs()
        super.onCreate(savedInstanceState)
        val frame = findViewById<FrameLayout>(com.termux.x11.R.id.frame) ?: return
        frame.addView(
            ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent { DisplayOverlay(this@DisplayActivity) }
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        AppLog.i("display activity created")
    }

    /** Our controls settings drive the matching Termux:X11 preferences. */
    private fun applyControlsToTermuxPrefs() {
        val c = app.controls.config.value
        val p = app.builtInPrefs
        if (p.fullscreen.get() != c.fullscreen) p.fullscreen.put(c.fullscreen)
        if (p.hideCutout.get() != c.hideCutout) p.hideCutout.put(c.hideCutout)
        val idle = if (c.keepScreenOn) "never" else "system"
        if (p.screenIdleTimeout.get() != idle) p.screenIdleTimeout.put(idle)
        if (!c.termuxDefaultsApplied) {
            // Termux:X11 shows a two-row extra keys bar by default, which takes a quarter of
            // a phone screen. Start hidden; the menu / a gesture brings it back.
            p.additionalKbdVisible.put(false)
            app.controls.update { it.copy(termuxDefaultsApplied = true) }
        }
    }

    override fun onResume() {
        applyControlsToTermuxPrefs()
        super.onResume()
    }

    // --- Back gesture -----------------------------------------------------------------

    /**
     * The system back gesture (or button) opens and closes the menu. Multi-finger gestures
     * are not reliable on every phone: ColorOS, for one, takes three fingers for itself.
     * A mouse's back button keeps its Termux:X11 meaning (right click).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val mouse = event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
        if (event.keyCode != KeyEvent.KEYCODE_BACK || mouse) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0 && !event.isCanceled && !editingButtons.value) {
            menuOpen.value = !menuOpen.value
        }
        return true
    }

    // --- Multi-finger gestures -------------------------------------------------------

    private var tracking = false
    private var frozen = false
    private var maxPointers = 0
    private val startPos = HashMap<Int, Pair<Float, Float>>()
    private var dx = 0f
    private var dy = 0f
    private var startTime = 0L
    private var cancelHintShown = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (editingButtons.value || menuOpen.value) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { tracking = false; maxPointers = 1 }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, ev.pointerCount)
                if (!tracking && ev.pointerCount >= 3) {
                    tracking = true
                    frozen = false
                    dx = 0f; dy = 0f
                    startPos.clear()
                    startTime = SystemClock.uptimeMillis()
                    // Take the touch stream away from the X input handler.
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
                if (tracking && !frozen) {
                    // A finger that lands late starts measuring from where it landed.
                    for (j in 0 until ev.pointerCount) startPos.getOrPut(ev.getPointerId(j)) { ev.getX(j) to ev.getY(j) }
                }
            }
        }
        if (!tracking) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (!frozen) measure(ev)
            // Fingers never lift at exactly the same time. Measuring the centroid after the
            // first one lifts makes it jump by a third of the finger spread, which turned
            // every tap into a "swipe" — so the movement is fixed at the first lift.
            MotionEvent.ACTION_POINTER_UP -> { if (!frozen) measure(ev); frozen = true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    val g = classify()
                    AppLog.d("gesture: ${maxPointers} fingers, moved ${"%.0f".format(hypot(dx, dy) / resources.displayMetrics.density)}dp in ${SystemClock.uptimeMillis() - startTime}ms -> ${g?.id}")
                    g?.let { onGesture(it) }
                } else {
                    AppLog.d("gesture cancelled by the system (OEM three-finger gesture?)")
                    if (!cancelHintShown) {
                        cancelHintShown = true
                        toast("Жесты тремя пальцами забирает система. Меню открывается жестом «назад».")
                    }
                }
            }
        }
        return true
    }

    /** Average movement of the fingers since each of them touched down. */
    private fun measure(ev: MotionEvent) {
        var sx = 0f; var sy = 0f; var n = 0
        for (i in 0 until ev.pointerCount) {
            val s = startPos[ev.getPointerId(i)] ?: continue
            sx += ev.getX(i) - s.first; sy += ev.getY(i) - s.second; n++
        }
        if (n > 0) { dx = sx / n; dy = sy / n }
    }

    private fun classify(): Gesture? {
        val density = resources.displayMetrics.density
        val dist = hypot(dx, dy) / density
        val dt = SystemClock.uptimeMillis() - startTime
        val four = maxPointers >= 4
        return when {
            dist < 40 && dt < 700 -> if (four) Gesture.TAP4 else Gesture.TAP3
            dist < 60 -> null
            abs(dy) > abs(dx) -> if (dy < 0) (if (four) Gesture.UP4 else Gesture.UP3) else (if (four) Gesture.DOWN4 else Gesture.DOWN3)
            four -> null
            dx < 0 -> Gesture.LEFT3
            else -> Gesture.RIGHT3
        }
    }

    private fun onGesture(g: Gesture) {
        val action = app.controls.config.value.gesture(g)
        AppLog.d("gesture ${g.id} -> $action")
        perform(action)
    }

    // --- Actions ---------------------------------------------------------------------

    fun perform(action: String) {
        when {
            action.startsWith("keys:") -> sendKeys(action.removePrefix("keys:"))
            action.startsWith("cmd:") -> {
                val cmd = action.removePrefix("cmd:")
                if (!app.sessions.launchInGuest(cmd)) toast("Нет запущенной сессии")
            }
            action.startsWith("app:") -> performApp(action.removePrefix("app:"))
        }
    }

    private fun sendKeys(combo: String) {
        val codes = Actions.parseKeys(combo) ?: return toast("Неизвестная клавиша в «$combo»")
        val view = lorieView
        codes.forEach { view.sendKeyEvent(0, it, true) }
        codes.asReversed().forEach { view.sendKeyEvent(0, it, false) }
    }

    private fun click(button: Int) {
        val view = lorieView
        view.sendMouseEvent(0f, 0f, button, true, true)
        view.sendMouseEvent(0f, 0f, button, false, true)
    }

    private fun performApp(id: String) {
        when (AppAction.entries.firstOrNull { it.id == id }) {
            AppAction.MENU -> menuOpen.value = !menuOpen.value
            AppAction.KEYBOARD -> toggleKeyboardVisibility()
            AppAction.EXTRA_KEYS -> toggleExtraKeys()
            AppAction.TOUCH_MODE -> {
                // 1 = trackpad, 3 = direct touch.
                val next = if (prefs.touchMode.get() == "1") "3" else "1"
                prefs.touchMode.put(next)
                toast(if (next == "1") "Режим: тачпад" else "Режим: прямое касание")
            }
            AppAction.BUTTONS -> app.controls.update { it.copy(showButtons = !it.showButtons) }
            AppAction.RIGHT_CLICK -> click(InputStub.BUTTON_RIGHT)
            AppAction.MIDDLE_CLICK -> click(InputStub.BUTTON_MIDDLE)
            AppAction.HOME -> goHome()
            AppAction.STOP -> {
                SessionService.send(this, SessionService.ACTION_STOP)
                goHome()
            }
            null -> {}
        }
    }

    fun goHome() {
        startActivity(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private var current: java.lang.ref.WeakReference<DisplayActivity>? = null

        fun finishCurrent() {
            val a = current?.get() ?: return
            a.runOnUiThread { if (!a.isFinishing) a.finish() }
        }
    }
}
