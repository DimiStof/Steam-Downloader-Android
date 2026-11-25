package com.example.steamfetch

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/** Foreground service so a multi-gigabyte download survives the screen turning off. */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.example.steamfetch.START"
        const val ACTION_CANCEL = "com.example.steamfetch.CANCEL"
        const val EXTRA_APP_ID = "appId"
        const val EXTRA_NAME = "name"
        const val EXTRA_OS = "os"
        const val EXTRA_DIR = "dir"
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1
        private const val DONE_NOTIF_ID = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                job?.cancel()
                Downloads.state.value = DlState.Idle
                finish()
            }

            ACTION_START -> {
                val appId = intent.getIntExtra(EXTRA_APP_ID, 0)
                val name = intent.getStringExtra(EXTRA_NAME) ?: "App $appId"
                val os = intent.getStringExtra(EXTRA_OS) ?: "windows"
                val baseDir = intent.getStringExtra(EXTRA_DIR) ?: AppSettings.defaultDir()

                ensureChannel()
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID,
                    buildNotification(name, "Starting…", null),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
                if (job?.isActive == true) return START_NOT_STICKY

                acquireWakeLock()
                job = scope.launch { execute(appId, name, os, baseDir) }
            }

            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun execute(appId: Int, name: String, os: String, baseDir: String) {
        val dir = File(baseDir, safeName(name, appId))
        Downloads.state.value = DlState.Active(appId, name, "Preparing…", 0f)
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                throw IllegalStateException("Can't create ${dir.absolutePath}. Check file access permission and the folder path.")
            }
            DepotEngine.download(appId, dir, os) { status, percent ->
                val current = Downloads.state.value
                val p = percent ?: (current as? DlState.Active)?.percent ?: 0f
                Downloads.state.value = DlState.Active(appId, name, status, p)
                throttledNotify(name, status, percent)
            }
            Downloads.state.value = DlState.Done(name, dir.absolutePath)
            notifyDone("$name finished", dir.absolutePath)
        } catch (e: CancellationException) {
            Downloads.state.value = DlState.Idle
            throw e
        } catch (e: Throwable) {
            Downloads.state.value = DlState.Failed(name, e.message ?: e.javaClass.simpleName)
            notifyDone("$name failed", e.message ?: e.javaClass.simpleName)
        } finally {
            finish()
        }
    }

    private fun finish() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SteamFetch:download").apply {
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(title: String, text: String, percent: Float?): Notification {
        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, (percent ?: 0f).toInt().coerceIn(0, 100), percent == null)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun throttledNotify(title: String, text: String, percent: Float?) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000) return
        lastNotify = now
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification(title, text, percent))
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyDone(title: String, text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(DONE_NOTIF_ID, n) }
    }

    private fun safeName(name: String, appId: Int): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "app_$appId" }
}
