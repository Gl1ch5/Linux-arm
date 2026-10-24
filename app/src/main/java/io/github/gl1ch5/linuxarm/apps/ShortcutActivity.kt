package io.github.gl1ch5.linuxarm.apps

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.gl1ch5.linuxarm.R
import io.github.gl1ch5.linuxarm.core.AppLog
import io.github.gl1ch5.linuxarm.runtime.SessionService

/**
 * Target of home-screen shortcuts: starts the distro if needed, then the program, and
 * shows the display. Has no UI of its own.
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val distro = intent.getStringExtra(EXTRA_DISTRO)
        val command = intent.getStringExtra(EXTRA_COMMAND)
        AppLog.i("shortcut: distro=$distro command=$command")
        // The activity must be exported for the launcher; the token keeps other apps from
        // using it to run arbitrary commands inside the distro.
        if (intent.getStringExtra(EXTRA_TOKEN) != token(this)) {
            AppLog.w("shortcut rejected: bad token")
        } else if (distro != null && command != null) {
            SessionService.send(this, SessionService.ACTION_LAUNCH_APP, distro, command)
        }
        finish()
    }

    companion object {
        const val EXTRA_DISTRO = "distro"
        const val EXTRA_COMMAND = "command"
        private const val EXTRA_TOKEN = "token"

        private fun token(context: Context): String {
            val prefs = context.getSharedPreferences("shortcuts", Context.MODE_PRIVATE)
            return prefs.getString("token", null) ?: java.util.UUID.randomUUID().toString().also {
                prefs.edit().putString("token", it).apply()
            }
        }

        /** Asks the launcher to pin a shortcut for [app]; returns false if the launcher can't. */
        fun pin(context: Context, distroId: String, distroName: String, app: DesktopApp): Boolean {
            if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
            val icon = app.iconFile?.let { f -> runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() }
                ?.let { IconCompat.createWithAdaptiveBitmap(padForAdaptive(it)) }
                ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher)
            val intent = Intent(context, ShortcutActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_DISTRO, distroId)
                .putExtra(EXTRA_COMMAND, DesktopApps.command(app))
                .putExtra(EXTRA_TOKEN, token(context))
            val info = ShortcutInfoCompat.Builder(context, "$distroId:${app.id}")
                .setShortLabel(app.name)
                .setLongLabel("${app.name} ($distroName)")
                .setIcon(icon)
                .setIntent(intent)
                .build()
            return ShortcutManagerCompat.requestPinShortcut(context, info, null)
        }

        /** Adaptive icons crop the outer third; center the program icon on a white tile. */
        private fun padForAdaptive(src: android.graphics.Bitmap): android.graphics.Bitmap {
            val size = 432
            val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            c.drawColor(0xFFF2F4F8.toInt())
            val inner = (size * 0.5f).toInt()
            val left = (size - inner) / 2f
            c.drawBitmap(src, null, android.graphics.RectF(left, left, left + inner, left + inner),
                android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            return out
        }
    }
}
