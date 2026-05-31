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
        private const val CHANNEL_CPU_ID = "perf_monitor_cpu_channel"
        private const val CHANNEL_NET_ID = "perf_monitor_net_channel"
        
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
            
            // Start in foreground immediately to satisfy Android OS requirements
            val initialStats = statsCollector.sample()
            val notification = buildCpuNotification(initialStats)
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
                
                // 1. Update primary CPU notification
                val cpuNotification = buildCpuNotification(stats)
                notificationManager.notify(NOTIFICATION_ID, cpuNotification)

                // 2. Update secondary Network speed notification
                val netNotification = buildNetworkNotification(stats)
                notificationManager.notify(NOTIFICATION_ID_NET, netNotification)
                
                delay(2000) // Sample every 2 seconds
            }
        }
    }

    private fun buildCpuNotification(stats: PerformanceStats): Notification {
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
            Notification.Builder(this, CHANNEL_CPU_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        // Try to generate a dynamic colorful MenuMeters-style status bar icon
        val dynamicIconBitmap = createMenuMetersBitmap(cpuPercent, stats.rxSpeedBytesPerSec, stats.txSpeedBytesPerSec)
        if (dynamicIconBitmap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.setSmallIcon(Icon.createWithBitmap(dynamicIconBitmap))
        } else {
            // Fallback to standard launch icon
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
            .setGroup("group_cpu")
            .build()
    }

    private fun buildNetworkNotification(stats: PerformanceStats): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            1, // Distinct request code
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val rxSpeedStr = formatSpeed(stats.rxSpeedBytesPerSec)
        val txSpeedStr = formatSpeed(stats.txSpeedBytesPerSec)

        val title = "SchoenMon Network Speeds"
        val content = "Download: ↓$rxSpeedStr | Upload: ↑$txSpeedStr"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_NET_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        // Try to generate a dynamic numerical speed status bar icon
        val dynamicNetBitmap = createNetSpeedBitmap(stats.rxSpeedBytesPerSec, stats.txSpeedBytesPerSec)
        if (dynamicNetBitmap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.setSmallIcon(Icon.createWithBitmap(dynamicNetBitmap))
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
            .setWhen(serviceStartTime + 1000L)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setGroup("group_net")
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // 1. CPU Monitor Channel
            val cpuChannel = NotificationChannel(
                CHANNEL_CPU_ID,
                "SchoenMon CPU Monitor",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Displays live CPU graph bars in status bar"
                setShowBadge(false)
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }
            manager.createNotificationChannel(cpuChannel)

            // 2. Network Speeds Channel
            val netChannel = NotificationChannel(
                CHANNEL_NET_ID,
                "SchoenMon Network Speeds",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Displays live numerical network speeds in status bar"
                setShowBadge(false)
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }
            manager.createNotificationChannel(netChannel)
        }
    }

    private fun createMenuMetersBitmap(cpuPercent: Float, rxSpeed: Long, txSpeed: Long): Bitmap? {
        return try {
            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
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

            val trackTop = 2f
            val trackBottom = 46f
            val trackHeight = trackBottom - trackTop // 44f
            val cornerRadius = 2f

            // 1. CPU Bar (Left): X from 5 to 13
            val cpuRatio = (cpuPercent / 100f).coerceIn(0f, 1f)
            val cpuFillHeight = trackHeight * cpuRatio
            val cpuTop = trackBottom - cpuFillHeight
            canvas.drawRoundRect(5f, trackTop, 13f, trackBottom, cornerRadius, cornerRadius, trackPaint)
            if (cpuFillHeight > 0) {
                canvas.drawRoundRect(5f, cpuTop, 13f, trackBottom, cornerRadius, cornerRadius, cpuPaint)
            }

            // 2. Net RX Bar (Middle): X from 19 to 27
            val rxRatio = getNetworkRatio(rxSpeed)
            val rxFillHeight = trackHeight * rxRatio
            val rxTop = trackBottom - rxFillHeight
            canvas.drawRoundRect(19f, trackTop, 27f, trackBottom, cornerRadius, cornerRadius, trackPaint)
            if (rxFillHeight > 0) {
                canvas.drawRoundRect(19f, rxTop, 27f, trackBottom, cornerRadius, cornerRadius, rxPaint)
            }

            // 3. Net TX Bar (Right): X from 33 to 41
            val txRatio = getNetworkRatio(txSpeed)
            val txFillHeight = trackHeight * txRatio
            val txTop = trackBottom - txFillHeight
            canvas.drawRoundRect(33f, trackTop, 41f, trackBottom, cornerRadius, cornerRadius, trackPaint)
            if (txFillHeight > 0) {
                canvas.drawRoundRect(33f, txTop, 41f, trackBottom, cornerRadius, cornerRadius, txPaint)
            }

            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun createNetSpeedBitmap(rxSpeed: Long, txSpeed: Long): Bitmap? {
        return try {
            val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT)

            val rxPaint = Paint().apply {
                color = Color.parseColor("#00E676") // Neo Green
                textSize = 19f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            val txPaint = Paint().apply {
                color = Color.parseColor("#D500F9") // Electric Pink
                textSize = 19f
                isAntiAlias = true
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            val rxStr = "↓" + formatShortSpeed(rxSpeed)
            val txStr = "↑" + formatShortSpeed(txSpeed)

            // Centering calculations for top half and bottom half on a 48x48 canvas
            canvas.drawText(rxStr, 24f, 17f, rxPaint)
            canvas.drawText(txStr, 24f, 41f, txPaint)

            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun formatShortSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1fM", bytesPerSec / 1024f / 1024f)
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
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID_NET)
        
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
