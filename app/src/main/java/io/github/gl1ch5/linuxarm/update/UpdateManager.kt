package io.github.gl1ch5.linuxarm.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import io.github.gl1ch5.linuxarm.BuildConfig
import io.github.gl1ch5.linuxarm.R
import io.github.gl1ch5.linuxarm.catalog.JsonFormat
import io.github.gl1ch5.linuxarm.core.AppLog
import io.github.gl1ch5.linuxarm.net.Http
import io.github.gl1ch5.linuxarm.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/** version.json published by build-apk next to every APK. */
@Serializable
data class VersionInfo(
    @SerialName("version_name") val versionName: String,
    @SerialName("version_code") val versionCode: Int,
    val commit: String = "",
    val branch: String = "",
    val channel: String = "",
    val date: String = "",
    val apk: String,
    val sha256: String,
    val size: Long = 0,
    val changes: List<String> = emptyList(),
)

enum class UpdateChannel(val id: String, val label: String, val description: String) {
    TEST("test", "Тестовые", "Каждый push в репозиторий (релиз apk-latest)"),
    STABLE("stable", "Стабильные", "Только релизы с тегом v*"),
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Downloading(val fraction: Float) : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Polls version.json every minute while the app process is alive, notifies about new
 * builds and installs them through PackageInstaller after verifying the SHA-256.
 */
class UpdateManager(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    private val _latest = MutableStateFlow<VersionInfo?>(null)
    val latest: StateFlow<VersionInfo?> = _latest
    private val _lastCheck = MutableStateFlow(0L)
    val lastCheck: StateFlow<Long> = _lastCheck
    private val _checkError = MutableStateFlow<String?>(null)
    val checkError: StateFlow<String?> = _checkError
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private val _channel = MutableStateFlow(
        UpdateChannel.entries.firstOrNull { it.id == prefs.getString("channel", null) } ?: UpdateChannel.TEST
    )
    val channel: StateFlow<UpdateChannel> = _channel
    private val _autoCheck = MutableStateFlow(prefs.getBoolean("auto", true))
    val autoCheck: StateFlow<Boolean> = _autoCheck

    private var loop: Job? = null

    val currentCode = BuildConfig.VERSION_CODE
    fun isNewer(v: VersionInfo?) = v != null && v.versionCode > currentCode

    private fun url(file: String) = when (_channel.value) {
        UpdateChannel.TEST -> "https://github.com/${BuildConfig.GITHUB_REPO}/releases/download/apk-latest/$file"
        UpdateChannel.STABLE -> "https://github.com/${BuildConfig.GITHUB_REPO}/releases/latest/download/$file"
    }

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                if (_autoCheck.value) check()
                delay(60_000)
            }
        }
    }

    fun setChannel(c: UpdateChannel) {
        _channel.value = c
        prefs.edit().putString("channel", c.id).apply()
        _latest.value = null
        scope.launch { check() }
    }

    fun setAutoCheck(on: Boolean) {
        _autoCheck.value = on
        prefs.edit().putBoolean("auto", on).apply()
    }

    suspend fun check(): VersionInfo? {
        val v = runCatching { JsonFormat.decodeFromString<VersionInfo>(Http.getText(url("version.json"))) }
            .onFailure { _checkError.value = it.message ?: it.toString() }
            .getOrNull()
        _lastCheck.value = System.currentTimeMillis()
        if (v != null) {
            _checkError.value = null
            if (v != _latest.value) AppLog.d("update check: latest ${v.versionName} (${v.versionCode}), installed $currentCode")
            _latest.value = v
            if (isNewer(v) && prefs.getInt("notified", 0) != v.versionCode) {
                prefs.edit().putInt("notified", v.versionCode).apply()
                notifyUpdate(v)
            }
        }
        return v
    }

    private fun notifyUpdate(v: VersionInfo) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("updates", "Обновления", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 10,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "updates"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(2001, NotificationCompat.Builder(context, "updates")
            .setSmallIcon(R.drawable.ic_stat_linux)
            .setContentTitle("Доступна версия ${v.versionName}")
            .setContentText(v.changes.firstOrNull()?.substringAfter(' ') ?: "Нажмите, чтобы обновить")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    fun canInstallPackages() = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Downloads the APK of [v], checks its SHA-256 and hands it to the system installer. */
    fun install(v: VersionInfo) {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        scope.launch {
            try {
                _state.value = UpdateState.Downloading(0f)
                val dir = File(context.cacheDir, "update").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val apk = File(dir, "update-${v.versionCode}.apk")
                Http.download(url(v.apk), apk, v.sha256) { done, total ->
                    val t = if (total > 0) total else v.size
                    _state.value = UpdateState.Downloading(if (t > 0) done.toFloat() / t else 0f)
                }
                _state.value = UpdateState.Installing
                AppLog.i("installing update ${v.versionName} (${v.versionCode})")
                withContext(Dispatchers.IO) { installApk(apk) }
            } catch (e: Exception) {
                AppLog.e("update failed", e)
                _state.value = UpdateState.Failed(e.message ?: e.toString())
            }
        }
    }

    private fun installApk(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            apk.inputStream().use { input ->
                s.openWrite("base.apk", 0, apk.length()).use { out -> input.copyTo(out); s.fsync(out) }
            }
            val status = PendingIntent.getBroadcast(context, id,
                Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(status.intentSender)
        }
    }

    internal fun onInstallResult(status: Int, message: String?) {
        AppLog.i("install result: status=$status message=$message")
        _state.value = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> UpdateState.Installing
            PackageInstaller.STATUS_SUCCESS -> UpdateState.Idle
            else -> UpdateState.Failed(
                if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)
                    "Подпись не совпадает с установленной версией (тестовая и стабильная сборки подписаны разными ключами). " +
                        "Удалите приложение и установите новую версию вручную. ($message)"
                else message ?: "ошибка установки (код $status)"
            )
        }
    }
}

/** Receives PackageInstaller results; shows the system confirmation dialog when needed. */
class InstallResultReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
        }
        (context.applicationContext as io.github.gl1ch5.linuxarm.App).updates.onInstallResult(status, message)
    }
}
