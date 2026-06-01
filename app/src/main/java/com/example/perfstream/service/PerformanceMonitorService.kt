package com.example.perfstream.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import com.example.perfstream.MainActivity
import com.example.perfstream.R
import com.example.perfstream.core.PerformanceStats
import com.example.perfstream.core.StatsCollector
import com.example.perfstream.data.PerformanceMonitorRepository
import com.example.perfstream.surface.LiveUpdateController
import com.example.perfstream.surface.PerfSurfaces
import com.example.perfstream.surface.SpeedIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class PerformanceMonitorService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private lateinit var statsCollector: StatsCollector
    private var isRunning = false
    private var samplingJob: Job? = null
    private var serviceStartTime: Long = 0L

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "perf_monitor_channel"
        private const val CHANNEL_NAME = "SchoenMon Performance"

        fun startService(context: Context) {
            val intent = Intent(context, PerformanceMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, PerformanceMonitorService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        statsCollector = StatsCollector(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            serviceStartTime = System.currentTimeMillis()
            createNotificationChannel()
            
            val initialStats = statsCollector.sample()
            val notification = buildNotification(initialStats)
            startForeground(NOTIFICATION_ID, notification)

            startSamplingLoop()
        }
        return START_STICKY
    }

    private fun startSamplingLoop() {
        samplingJob = serviceScope.launch {
            while (isRunning) {
                val stats = statsCollector.sample()
                PerformanceMonitorRepository.updateStats(stats)
                
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, buildNotification(stats))

                // Fan the same sample out to every other glanceable surface.
                PerfSurfaces.refreshAll(applicationContext, stats)

                delay(2000)
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Regular foreground-service notification: NUMERIC NETWORK METER.
    //  Network up/down arrow icon in the status bar; the ↓/↑ byte rates are
    //  the prominent title text (the only place numbers are actually legible -
    //  Samsung's AppIconSolution rewrites any bitmap-rendered numeric glyph to
    //  the launcher icon, so numbers live in the text, not the icon). CPU now
    //  lives in the separate promoted Live Update, so this notification gets
    //  its own status bar icon distinct from the CPU chip.
    // ──────────────────────────────────────────────

    private fun buildNotification(stats: PerformanceStats): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val rxSpeedStr = formatSpeed(stats.rxSpeedBytesPerSec)
        val txSpeedStr = formatSpeed(stats.txSpeedBytesPerSec)
        val cpuPercent = stats.avgCpuFrequencyPercent
        val ramUsedGb = stats.usedMemoryBytes / 1024f / 1024f / 1024f
        val ramTotalGb = stats.totalMemoryBytes / 1024f / 1024f / 1024f

        val title = String.format(
            Locale.US,
            "↓%s  ↑%s",
            rxSpeedStr, txSpeedStr
        )
        val content = String.format(
            Locale.US,
            "CPU %.0f%%  -  RAM %.1f/%.1fG",
            cpuPercent, ramUsedGb, ramTotalGb
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        // Dynamic numeric icon: download rate as number-over-unit (e.g. 1.1 / M/s).
        builder.setSmallIcon(SpeedIcon.forSpeed(stats.rxSpeedBytesPerSec))
        builder.setColor(Color.parseColor("#00E676")) // Neo Green = network

        return builder
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setWhen(serviceStartTime)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .build()
    }

    // ──────────────────────────────────────────────
    //  Channel
    // ──────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SchoenMon performance monitoring"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    // ──────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────

    private fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1fM", bytesPerSec / 1024f / 1024f)
            bytesPerSec >= 1024 -> String.format(Locale.US, "%.0fK", bytesPerSec / 1024f)
            else -> "${bytesPerSec}B"
        }
    }

    override fun onDestroy() {
        isRunning = false
        samplingJob?.cancel()
        serviceJob.cancel()
        // Tear down the Now Bar live update so it doesn't linger after stop.
        LiveUpdateController.cancel(applicationContext)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
