package com.sticktoitive.schoenmon.surface

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.sticktoitive.schoenmon.MainActivity
import com.sticktoitive.schoenmon.R
import com.sticktoitive.schoenmon.core.PerformanceStats
import com.sticktoitive.schoenmon.core.StatFormat
import com.sticktoitive.schoenmon.data.PerformanceMonitorRepository

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

        // Graph bitmap pixel size (scaled to fit the 40dp ImageView). Wide so
        // the rolling history reads as a trend on the home/lock screen.
        private const val GRAPH_W = 480
        private const val GRAPH_H = 120

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

            val graph = GraphIcon.forHistory(
                PerformanceMonitorRepository.history.value, GRAPH_W, GRAPH_H
            )
            views.setImageViewBitmap(R.id.widget_graph, graph)

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
