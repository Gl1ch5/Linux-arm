package io.github.gl1ch5.linuxarm.display

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
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

    // --- Multi-finger gestures -------------------------------------------------------

    private var tracking = false
    private var maxPointers = 0
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var startTime = 0L

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (editingButtons.value || menuOpen.value) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { tracking = false; maxPointers = 1 }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, ev.pointerCount)
                if (!tracking && ev.pointerCount >= 3) {
                    tracking = true
                    centroid(ev).let { (x, y) -> startX = x; startY = y; lastX = x; lastY = y }
                    startTime = SystemClock.uptimeMillis()
                    // Take the touch stream away from the X input handler.
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
            }
        }
        if (!tracking) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> centroid(ev).let { (x, y) -> lastX = x; lastY = y }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                if (ev.actionMasked == MotionEvent.ACTION_UP) classify()?.let { onGesture(it) }
            }
        }
        return true
    }

    private fun centroid(ev: MotionEvent): Pair<Float, Float> {
        var x = 0f; var y = 0f
        for (i in 0 until ev.pointerCount) { x += ev.getX(i); y += ev.getY(i) }
        return x / ev.pointerCount to y / ev.pointerCount
    }

    private fun classify(): Gesture? {
        val density = resources.displayMetrics.density
        val dx = lastX - startX
        val dy = lastY - startY
        val dist = hypot(dx, dy) / density
        val dt = SystemClock.uptimeMillis() - startTime
        val four = maxPointers >= 4
        return when {
            dist < 30 && dt < 500 -> if (four) Gesture.TAP4 else Gesture.TAP3
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
