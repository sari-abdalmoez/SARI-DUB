package com.saridub.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SariApp : Application() {
    lateinit var store: ProjectStore
    override fun onCreate() { super.onCreate(); store = ProjectStore(this) }
}

class ProcessingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        val id = i?.getStringExtra("id") ?: return START_NOT_STICKY
        if (ProcessingState.job?.isActive == true) return START_NOT_STICKY
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("proc", "Dubbing progress", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notif("Starting…", 0))
        ProcessingState.paused.set(false)
        ProcessingState.job = scope.launch {
            val watcher = launch { ProcessingState.progress.collect { pr -> nm.notify(1, notif("${pr.stage} ${pr.percent}%", pr.percent)) } }
            try {
                Pipeline(applicationContext).run(id)
            } catch (e: CancellationException) {
                ProcessingState.progress.update { it.copy(stage = "Cancelled", message = "Progress saved. Press Start to resume.", error = null) }
            } catch (e: PipelineError) {
                ProcessingState.progress.update { it.copy(stage = "Failed", error = e.message) }
            } catch (e: Throwable) {
                ProcessingState.progress.update { it.copy(stage = "Failed", error = "Unexpected failure: ${e.message}") }
            } finally {
                ProcessingState.progress.update { it.copy(running = false) }
                watcher.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notif(text: String, pct: Int): Notification =
        NotificationCompat.Builder(this, "proc").setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("SARI DUB").setContentText(text).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, pct, false).build()

    override fun onDestroy() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); super.onDestroy() }
}
