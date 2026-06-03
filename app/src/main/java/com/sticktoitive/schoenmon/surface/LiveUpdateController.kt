package com.sticktoitive.schoenmon.surface

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import com.sticktoitive.schoenmon.MainActivity
import com.sticktoitive.schoenmon.R
import com.sticktoitive.schoenmon.core.PerformanceStats
import com.sticktoitive.schoenmon.core.StatFormat

/**
 * Android 16 "Live Update" - the standardized promoted-ongoing notification
 * that Samsung One UI 8+ surfaces in the Now Bar (status bar chip + lock
 * screen pill). On One UI 8 this is the supported path into the Now Bar; on
 * One UI 7 and stock pre-16 it degrades to an ordinary ongoing notification.
 *
 * Honest trade-off: this is still a notification under the hood, so it does
 * appear in the shade. The win over our old approach is the Now Bar chip /
 * pill placement and the ProgressStyle bar, not escaping the notification
 * list entirely. Included so it can be compared head to head with the tile
 * and widget.
 */
object LiveUpdateController {

    private const val CHANNEL_ID = "perf_live_update"
    private const val CHANNEL_NAME = "Now Bar Live Update"
    private const val LIVE_UPDATE_ID = 2001

    /** True on builds new enough to promote a notification to a Live Update. */
    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= 36

    /**
     * Post the CPU Live Update. On SDK 36+ One UI promotes this to its own Now
     * Bar chip (status bar pill), which sits SEPARATELY from the regular
     * foreground-service notification's icon - so the two read as two distinct
     * glanceable elements rather than getting collapsed together. The regular
     * notification carries the numeric network meter (see the service).
     */
    fun update(context: Context, stats: PerformanceStats) {
        if (!supported) return
        if (!hasPostPermission(context)) return
        postLiveUpdate(context, stats)
    }

    @RequiresApi(36)
    private fun postLiveUpdate(context: Context, stats: PerformanceStats) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)

        val tap = PendingIntent.getActivity(
            context,
            7,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cpu = StatFormat.cpuPercent(stats)
        val ram = StatFormat.ramPercent(stats)

        // ProgressStyle bar: fill proportional to CPU load, with a second
        // segment hinting RAM. Segment "durations" are arbitrary weights.
        val style = Notification.ProgressStyle()
            .setProgressSegments(
                listOf(
                    Notification.ProgressStyle.Segment(100)
                        .setColor(Color.parseColor("#00E5FF")), // Cyber Cyan = CPU
                )
            )
            .setProgress(cpu)
            .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.ic_tile_cpu))
        // ProgressStyle defaults to a max of 100, matching our percentage.

        // Colored CPU (cyan) + RAM (pink) filled bars as the status bar icon.
        // Bitmap keeps its colors in the One UI status bar. Bars over a graph
        // here: a sparkline is illegible shrunk into the ~24px status-bar slot,
        // two filled bars read at a glance. The graph lives in the widget /
        // lock screen where it has real estate.
        val barsIcon = BarsIcon.forLoads(cpu, ram)

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(barsIcon)
            .setColor(Color.parseColor("#00E5FF"))
            .setContentTitle("CPU ${StatFormat.cpuLabel(stats)}  RAM ${ram}%")
            .setContentText("${StatFormat.netLabel(stats)}  -  ${StatFormat.ramLabel(stats)}")
            .setStyle(style)
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // Status bar chip text on One UI / Android 16 Now Bar.
            .setShortCriticalText("${cpu}%")

        // Ask the system to promote this to a Live Update (Now Bar). The
        // platform Notification.Builder has no public setter and the
        // EXTRA_REQUEST_PROMOTED_ONGOING constant is @hide, so we set its
        // documented stable string value directly. NotificationCompat.Builder
        // exposes the same thing as setRequestPromotedOngoing(true).
        builder.addExtras(Bundle().apply {
            putBoolean("android.requestPromotedOngoing", true)
        })

        manager.notify(LIVE_UPDATE_ID, builder.build())
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(LIVE_UPDATE_ID)
    }

    private fun hasPostPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            // Must be > IMPORTANCE_MIN for Live Updates to surface reliably.
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Live CPU/RAM/Net in the Now Bar"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
