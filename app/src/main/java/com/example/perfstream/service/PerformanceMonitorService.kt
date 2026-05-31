package com.example.perfstream.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import com.example.perfstream.MainActivity
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
        private const val NOTIFICATION_ID_NET = 1002
        private const val CHANNEL_ID = "perf_monitor_channel"
        private const val CHANNEL_NAME = "SchoenMon Performance"
        private const val CHANNEL_NET_ID = "perf_monitor_net_channel"
        private const val CHANNEL_NET_NAME = "SchoenMon Network Speeds"
        
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
            createNotificationChannels()
            
            // Start in foreground immediately to satisfy Android OS requirements
            val initialStats = statsCollector.sample()
            val notification = buildBarNotification(initialStats)
            startForeground(NOTIFICATION_ID, notification)

            startSamplingLoop()
        }
        return START_STICKY
    }

    private fun startSamplingLoop() {
        samplingJob = serviceScope.launch {
            while (isRunning) {
                val stats = statsCollector.sample()
                // Update shared repository for Compose UI
                PerformanceMonitorRepository.updateStats(stats)
                
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                // 1. Update primary bar-graph notification (the foreground service notification)
                val barNotification = buildBarNotification(stats)
                notificationManager.notify(NOTIFICATION_ID, barNotification)

                // 2. Update secondary numeric network speed notification
                val netNotification = buildNetSpeedNotification(stats)
                notificationManager.notify(NOTIFICATION_ID_NET, netNotification)
                
                delay(2000) // Sample every 2 seconds
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Notification 1 — Colored Bars (foreground)
    // ──────────────────────────────────────────────

    private fun buildBarNotification(stats: PerformanceStats): Notification {
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

        val title = "SchoenMon Live Monitor"
        val content = String.format(
            Locale.US,
            "CPU: %.0f%% | NET: ↓%s ↑%s | RAM: %.1fG/%.1fG",
            cpuPercent, rxSpeedStr, txSpeedStr, ramUsedGb, ramTotalGb
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        // Dynamic MenuMeters-style 3-bar status icon (CPU · RX · TX)
        val dynamicIconBitmap = createMenuMetersBitmap(cpuPercent, stats.rxSpeedBytesPerSec, stats.txSpeedBytesPerSec)
        if (dynamicIconBitmap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.setSmallIcon(Icon.createWithBitmap(dynamicIconBitmap))
        } else {
            builder.setSmallIcon(com.example.perfstream.R.mipmap.ic_launcher)
        }

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
    //  Notification 2 — Numeric Network Speed
    // ──────────────────────────────────────────────

    private fun buildNetSpeedNotification(stats: PerformanceStats): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            1, // Distinct request code from bar notification
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val rxSpeedStr = formatSpeed(stats.rxSpeedBytesPerSec)
        val txSpeedStr = formatSpeed(stats.txSpeedBytesPerSec)

        val title = "SchoenMon Network"
        val content = "↓$rxSpeedStr  ↑$txSpeedStr"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_NET_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        // Dynamic numeric speed icon
        val netBitmap = createNetSpeedBitmap(stats.rxSpeedBytesPerSec, stats.txSpeedBytesPerSec)
        if (netBitmap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.setSmallIcon(Icon.createWithBitmap(netBitmap))
        } else {
            builder.setSmallIcon(com.example.perfstream.R.mipmap.ic_launcher)
        }

        return builder
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setWhen(serviceStartTime + 1000L) // Offset so Android sorts it after bar icon
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .build()
    }

    // ──────────────────────────────────────────────
    //  Notification Channels
    // ──────────────────────────────────────────────

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Primary channel – bar graph icon
            val barChannel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays live CPU/network bar graph in status bar"
                setShowBadge(false)
            }
            manager.createNotificationChannel(barChannel)

            // Secondary channel – numeric speed icon
            val netChannel = NotificationChannel(
                CHANNEL_NET_ID,
                CHANNEL_NET_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays live numerical network speeds in status bar"
                setShowBadge(false)
            }
            manager.createNotificationChannel(netChannel)
        }
    }

    // ──────────────────────────────────────────────
    //  Bitmap: 3-bar MenuMeters graph  (96 × 96)
    // ──────────────────────────────────────────────

    private fun createMenuMetersBitmap(cpuPercent: Float, rxSpeed: Long, txSpeed: Long): Bitmap? {
        return try {
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT)

            val trackPaint = Paint().apply {
                color = Color.argb(40, 255, 255, 255)
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            val cpuPaint = Paint().apply {
                color = Color.parseColor("#00E5FF") // Cyber Cyan
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            val rxPaint = Paint().apply {
                color = Color.parseColor("#00E676") // Neo Green
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            val txPaint = Paint().apply {
                color = Color.parseColor("#D500F9") // Electric Pink
                style = Paint.Style.FILL
                isAntiAlias = true
            }

            val trackTop = 4f
            val trackBottom = 92f
            val trackHeight = trackBottom - trackTop // 88f
            val cornerRadius = 4f

            // 1. CPU Bar (Left)
            val cpuRatio = (cpuPercent / 100f).coerceIn(0f, 1f)
            val cpuFillHeight = trackHeight * cpuRatio
            val cpuTop = trackBottom - cpuFillHeight
            canvas.drawRoundRect(11f, trackTop, 29f, trackBottom, cornerRadius, cornerRadius, trackPaint)
            if (cpuFillHeight > 0) {
                canvas.drawRoundRect(11f, cpuTop, 29f, trackBottom, cornerRadius, cornerRadius, cpuPaint)
            }

            // 2. Net RX Bar (Middle)
            val rxRatio = getNetworkRatio(rxSpeed)
            val rxFillHeight = trackHeight * rxRatio
            val rxTop = trackBottom - rxFillHeight
            canvas.drawRoundRect(39f, trackTop, 57f, trackBottom, cornerRadius, cornerRadius, trackPaint)
            if (rxFillHeight > 0) {
                canvas.drawRoundRect(39f, rxTop, 57f, trackBottom, cornerRadius, cornerRadius, rxPaint)
            }

            // 3. Net TX Bar (Right)
            val txRatio = getNetworkRatio(txSpeed)
            val txFillHeight = trackHeight * txRatio
            val txTop = trackBottom - txFillHeight
            canvas.drawRoundRect(67f, trackTop, 85f, trackBottom, cornerRadius, cornerRadius, trackPaint)
            if (txFillHeight > 0) {
                canvas.drawRoundRect(67f, txTop, 85f, trackBottom, cornerRadius, cornerRadius, txPaint)
            }

            bitmap
        } catch (e: Exception) {
            null
        }
    }

    // ──────────────────────────────────────────────
    //  Bitmap: Numeric speed readout  (96 × 96)
    // ──────────────────────────────────────────────

    private fun createNetSpeedBitmap(rxSpeed: Long, txSpeed: Long): Bitmap? {
        return try {
            val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT)

            val rxPaint = Paint().apply {
                color = Color.parseColor("#00E676") // Neo Green  (download)
                textSize = 36f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            val txPaint = Paint().apply {
                color = Color.parseColor("#D500F9") // Electric Pink  (upload)
                textSize = 36f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            val rxStr = "↓" + formatShortSpeed(rxSpeed)
            val txStr = "↑" + formatShortSpeed(txSpeed)

            // Top half for download, bottom half for upload
            canvas.drawText(rxStr, 48f, 34f, rxPaint)
            canvas.drawText(txStr, 48f, 82f, txPaint)

            bitmap
        } catch (e: Exception) {
            null
        }
    }

    // ──────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────

    private fun formatShortSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.0fM", bytesPerSec / 1024f / 1024f)
            bytesPerSec >= 1024 -> String.format(Locale.US, "%.0fK", bytesPerSec / 1024f)
            else -> "0K"
        }
    }

    private fun getNetworkRatio(speedBytesPerSec: Long): Float {
        if (speedBytesPerSec <= 0) return 0f

        // Logarithmic scale starting from 1 KB/s to 10 MB/s
        val minSpeed = 1024.0 // 1 KB/s
        val maxSpeed = 10.0 * 1024.0 * 1024.0 // 10 MB/s

        if (speedBytesPerSec < minSpeed) {
            // Linear scale for low speeds between 0 and 1 KB/s
            val linearRatio = (speedBytesPerSec.toDouble() / minSpeed).toFloat()
            // Map 0..1 KB/s to 0..0.15 of the bar height
            return (linearRatio * 0.15f).coerceIn(0f, 0.15f)
        }

        val logMin = Math.log(minSpeed)
        val logMax = Math.log(maxSpeed)
        val logVal = Math.log(speedBytesPerSec.toDouble())

        val logRatio = ((logVal - logMin) / (logMax - logMin)).toFloat()
        // Map 1 KB/s..10 MB/s to 0.15f..1.0f of the bar height
        return (0.15f + logRatio * 0.85f).coerceIn(0.15f, 1.0f)
    }

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

        // Clean up the secondary notification
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID_NET)

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
