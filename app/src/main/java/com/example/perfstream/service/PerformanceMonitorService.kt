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

        // Pre-rendered bar icon drawables (CPU bar 0%-100% in 10% steps).
        // Samsung One UI replaces bitmap/level-list icons with the launcher
        // icon, so we switch between static vector drawables by resource ID.
        private val BAR_ICONS = intArrayOf(
            R.drawable.ic_stat_bars_0,
            R.drawable.ic_stat_bars_1,
            R.drawable.ic_stat_bars_2,
            R.drawable.ic_stat_bars_3,
            R.drawable.ic_stat_bars_4,
            R.drawable.ic_stat_bars_5,
            R.drawable.ic_stat_bars_6,
            R.drawable.ic_stat_bars_7,
            R.drawable.ic_stat_bars_8,
            R.drawable.ic_stat_bars_9,
            R.drawable.ic_stat_bars_10,
        )
        
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
                val notification = buildNotification(stats)
                notificationManager.notify(NOTIFICATION_ID, notification)
                
                delay(2000)
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Single notification with dynamic bar icon
    //  CPU bar height changes every 2s via drawable
    //  switching; network speeds in notification text
    // ──────────────────────────────────────────────

    private fun buildNotification(stats: PerformanceStats): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cpuPercent = stats.avgCpuFrequencyPercent
        val rxSpeedStr = formatSpeed(stats.rxSpeedBytesPerSec)
        val txSpeedStr = formatSpeed(stats.txSpeedBytesPerSec)
        val ramUsedGb = stats.usedMemoryBytes / 1024f / 1024f / 1024f
        val ramTotalGb = stats.totalMemoryBytes / 1024f / 1024f / 1024f

        val title = String.format(
            Locale.US,
            "CPU: %.0f%% | ↓%s ↑%s",
            cpuPercent, rxSpeedStr, txSpeedStr
        )
        val content = String.format(
            Locale.US,
            "RAM: %.1fG/%.1fG",
            ramUsedGb, ramTotalGb
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        // Pick the right bar icon based on CPU load (0-10 → 0%-100%)
        val cpuLevel = (cpuPercent / 10f).toInt().coerceIn(0, 10)
        builder.setSmallIcon(BAR_ICONS[cpuLevel])
        builder.setColor(Color.parseColor("#00E5FF")) // Cyber Cyan accent

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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
