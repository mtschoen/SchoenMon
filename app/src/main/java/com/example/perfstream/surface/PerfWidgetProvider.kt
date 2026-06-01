package com.example.perfstream.surface

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.perfstream.MainActivity
import com.example.perfstream.R
import com.example.perfstream.core.PerformanceStats
import com.example.perfstream.core.StatFormat
import com.example.perfstream.data.PerformanceMonitorRepository

/**
 * Home and lock screen widget. Genuinely glanceable: it lives on a launcher
 * surface and the foreground service repaints it every sample tick via
 * [update]. Not an overlay - it only shows where you place it - but it never
 * sits over other apps and is never in the notification list.
 */
class PerfWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val stats = PerformanceMonitorRepository.stats.value
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context, stats))
        }
    }

    companion object {
        /** Push the latest sample to every placed instance of the widget. */
        fun update(context: Context, stats: PerformanceStats) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context, PerfWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            val views = buildViews(context, stats)
            for (id in ids) {
                manager.updateAppWidget(id, views)
            }
        }

        private fun buildViews(context: Context, stats: PerformanceStats?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_perf)
            if (stats == null) {
                views.setTextViewText(R.id.widget_cpu_value, "--")
                views.setTextViewText(R.id.widget_ram_value, "--")
                views.setTextViewText(R.id.widget_net_value, "--")
            } else {
                views.setTextViewText(R.id.widget_cpu_value, StatFormat.cpuLabel(stats))
                views.setTextViewText(R.id.widget_ram_value, StatFormat.ramLabel(stats))
                views.setTextViewText(R.id.widget_net_value, StatFormat.netLabel(stats))
            }

            val tap = PendingIntent.getActivity(
                context,
                42,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_root, tap)
            return views
        }
    }
}
