package com.sticktoitive.schoenmon.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.sticktoitive.schoenmon.BuildConfig
import com.sticktoitive.schoenmon.MainActivity
import com.sticktoitive.schoenmon.R
import com.sticktoitive.schoenmon.core.PerformanceStats
import com.sticktoitive.schoenmon.core.StatsCollector
import com.sticktoitive.schoenmon.core.TickProfiler
import com.sticktoitive.schoenmon.data.PerformanceMonitorRepository
import com.sticktoitive.schoenmon.surface.LiveUpdateController
import com.sticktoitive.schoenmon.surface.PerfSurfaces
import com.sticktoitive.schoenmon.surface.SpeedIcon
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
    private val profiler: TickProfiler? = if (BuildConfig.DEBUG) TickProfiler() else null

    // Pauses the sampling loop while the display is off: every glanceable
    // surface (status-bar icon, Now Bar chip, widget, tiles) is invisible with
    // the screen off, so waking the SoC every 2s to redraw them would be pure
    // battery waste. The service stays in the foreground (notification frozen on
    // its last frame); only the poll loop pauses. Resumes on screen-on.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> resumeSampling()
                Intent.ACTION_SCREEN_OFF -> pauseSampling()
            }
        }
    }

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
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            serviceStartTime = System.currentTimeMillis()
            createNotificationChannel()
            
            val initialStats = statsCollector.sample()
            val notification = buildNotification(initialStats)
            startForeground(NOTIFICATION_ID, notification)

            // Only start the loop if the screen is on; otherwise the screen-on
            // broadcast will start it when it's actually worth sampling.
            if (isScreenOn()) {
                startSamplingLoop()
            }
        }
        return START_STICKY
    }

    private fun startSamplingLoop() {
        samplingJob = serviceScope.launch {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            var tickCount = 0L

            while (isRunning) {
                profiler?.beginTick()

                val stats = statsCollector.sample()
                profiler?.markPhase("sample")

                PerformanceMonitorRepository.updateStats(stats)
                profiler?.markPhase("repoUpdate")

                val notification = buildNotification(stats)
                profiler?.markPhase("notifBuild")

                // Fire-and-forget: notification IPC and surface refreshes run
                // on the IO dispatcher and must NOT block the next sample.
                // This is the single biggest optimisation: profiling showed
                // notifPost + surfaces consumed ~1070ms / ~1190ms per tick.
                val tick = tickCount++
                launch(Dispatchers.IO) {
                    notificationManager.notify(NOTIFICATION_ID, notification)

                    // Fan the same sample out to every other glanceable surface.
                    // Live Update is debounced (every 2nd tick) because its
                    // ProgressStyle IPC averaged ~600ms alone.
                    PerfSurfaces.refreshAll(
                        applicationContext,
                        stats,
                        skipLiveUpdate = tick % 2 != 0L,
                    )
                }
                profiler?.markPhase("dispatch")

                profiler?.endTick()

                delay(500)
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Screen-state gating (battery)
    // ──────────────────────────────────────────────
    //
    //  XR EXCEPTION: Android XR headsets report PowerManager.isInteractive
    //  as false even while the user is actively wearing them (mWakefulness=
    //  Asleep). The screen on/off broadcast model doesn't apply to HMDs.
    //  On XR devices we always run the loop; the foreground service itself
    //  is the battery gate (the OS kills it when the headset truly sleeps).

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // ACTION_SCREEN_ON/OFF are protected system broadcasts, so the receiver
        // never needs to be exported.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }
    }

    /** On XR headsets the "screen" is always conceptually on. */
    private val isXrDevice: Boolean by lazy {
        packageManager.hasSystemFeature("android.software.xr.api.spatial")
    }

    private fun isScreenOn(): Boolean {
        if (isXrDevice) return true
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isInteractive
    }

    /** Screen turned off: stop sampling. The foreground notification freezes on
     *  its last frame; the service itself stays alive. */
    private fun pauseSampling() {
        samplingJob?.cancel()
        samplingJob = null
    }

    /** Screen turned on: resume sampling if we aren't already, re-baselining the
     *  network counters so the first frame shows the live rate, not a stale
     *  average accumulated while paused. */
    private fun resumeSampling() {
        if (!isRunning || samplingJob != null) return
        statsCollector.resetNetworkBaseline()
        startSamplingLoop()
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
        runCatching { unregisterReceiver(screenReceiver) }
        samplingJob?.cancel()
        serviceJob.cancel()
        // Tear down the Now Bar live update so it doesn't linger after stop.
        LiveUpdateController.cancel(applicationContext)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
