package io.github.gl1ch5.linuxarm.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.gl1ch5.linuxarm.App
import io.github.gl1ch5.linuxarm.R
import io.github.gl1ch5.linuxarm.core.AppLog
import io.github.gl1ch5.linuxarm.distro.DistroStatus
import io.github.gl1ch5.linuxarm.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and with it the X server and proot) alive
 * while a desktop runs or an image is being installed.
 */
class SessionService : LifecycleService() {
    private val app get() = application as App
    private val installJobs = mutableMapOf<String, Job>()
    private val otherJobs = mutableListOf<Job>()
    private var gotCommand = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Linux ARM", "Готово"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        lifecycleScope.launch {
            combine(app.sessions.running, app.distros.status, app.sessions.guard.count) { r, s, n -> Triple(r, s, n) }
                .collect { (running, status, procs) ->
                val installing = status.entries.firstOrNull { it.value is DistroStatus.Downloading || it.value is DistroStatus.Extracting }
                val (title, text) = when {
                    running != null -> "Запущен: ${nameOf(running.distroId)}" to
                        "GPU: ${running.gpu} · процессов: $procs"
                    installing != null -> "Установка: ${nameOf(installing.key)}" to when (val st = installing.value) {
                        is DistroStatus.Downloading -> "Загрузка ${(st.fraction * 100).toInt()}%"
                        is DistroStatus.Extracting -> "Распаковка ${(st.fraction * 100).toInt()}%"
                        else -> ""
                    }
                    else -> null to null
                }
                if (title == null) {
                    releaseWakeLock()
                    maybeStop()
                } else {
                    if (running != null) acquireWakeLock() else releaseWakeLock()
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(title, text ?: "", running != null))
                }
            }
        }
    }

    /** Stops the service once no session runs and no command is still in progress. */
    private fun maybeStop() {
        if (!gotCommand) return
        otherJobs.removeAll { !it.isActive }
        if (app.sessions.running.value == null && installJobs.values.none { it.isActive } && otherJobs.isEmpty()) {
            AppLog.d("service idle, stopping")
            stopSelf()
        }
    }

    private fun track(job: Job): Job {
        otherJobs += job
        job.invokeOnCompletion { lifecycleScope.launch { maybeStop() } }
        return job
    }

    private fun nameOf(id: String) = app.distros.installed.value[id]?.image?.name ?: app.catalog.find(id)?.name ?: id

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val id = intent?.getStringExtra(EXTRA_ID)
        gotCommand = true
        AppLog.d("service command ${intent?.action} $id")
        when (intent?.action) {
            ACTION_START -> if (id != null) track(lifecycleScope.launch { app.sessions.start(id) })
            ACTION_STOP -> track(lifecycleScope.launch { app.sessions.stop() })
            ACTION_INSTALL -> if (id != null && installJobs[id]?.isActive != true) {
                val image = app.catalog.find(id)
                if (image != null) {
                    installJobs[id] = lifecycleScope.launch { app.distros.install(image) }
                        .also { it.invokeOnCompletion { lifecycleScope.launch { maybeStop() } } }
                }
            }
            ACTION_CANCEL_INSTALL -> if (id != null) installJobs.remove(id)?.cancel()
            ACTION_OPEN -> app.sessions.openDisplay()
            ACTION_LAUNCH_APP -> {
                val command = intent.getStringExtra(EXTRA_COMMAND)
                if (id != null && command != null) track(lifecycleScope.launch {
                    if (app.sessions.running.value?.distroId != id) app.sessions.start(id)
                    if (app.sessions.running.value?.distroId == id) {
                        app.sessions.launchInGuest(command)
                        app.sessions.openDisplay()
                    }
                })
            }
            else -> track(lifecycleScope.launch { })
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "linuxarm:session").apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(title: String, text: String, running: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_linux)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
        if (running) {
            b.addAction(0, "Экран", PendingIntent.getService(this, 1, intent(this, ACTION_OPEN, null),
                PendingIntent.FLAG_IMMUTABLE))
            b.addAction(0, "Остановить", PendingIntent.getService(this, 2, intent(this, ACTION_STOP, null),
                PendingIntent.FLAG_IMMUTABLE))
        }
        return b.build()
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_INSTALL = "install"
        const val ACTION_CANCEL_INSTALL = "cancel_install"
        const val ACTION_OPEN = "open"
        const val ACTION_LAUNCH_APP = "launch_app"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_ID = "id"
        private const val CHANNEL = "session"
        private const val NOTIFICATION_ID = 1001

        fun intent(ctx: Context, action: String, id: String?) =
            Intent(ctx, SessionService::class.java).setAction(action).putExtra(EXTRA_ID, id)

        fun send(ctx: Context, action: String, id: String? = null, command: String? = null) {
            ContextCompat.startForegroundService(ctx, intent(ctx, action, id).putExtra(EXTRA_COMMAND, command))
        }

        fun createChannel(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Сессии и загрузки", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
