package com.sticktoitive.schoenmon.surface

import android.content.Context
import android.util.Log
import com.sticktoitive.schoenmon.core.PerformanceStats

/**
 * Single fan-out point the foreground service calls every sample tick. Keeps
 * the service ignorant of how many glanceable surfaces exist; each surface
 * decides for itself whether it has anything to refresh.
 */
object PerfSurfaces {
    private const val TAG = "SchoenMon.Perf.Surf"

    fun refreshAll(
        context: Context,
        stats: PerformanceStats,
        skipLiveUpdate: Boolean = false,
    ) {
        val t0 = System.nanoTime()

        // Quick Settings tiles (CPU / RAM / Net) - cheap, request a relisten.
        PerfTileService.requestUpdate(context)
        val t1 = System.nanoTime()

        // Home / lock screen widget.
        PerfWidgetProvider.update(context, stats)
        val t2 = System.nanoTime()

        // Now Bar / Android 16 Live Update — debounced by the caller because
        // the ProgressStyle IPC is the single most expensive operation (~600ms).
        var t3 = t2
        if (!skipLiveUpdate) {
            LiveUpdateController.update(context, stats)
            t3 = System.nanoTime()
        }

        Log.d(TAG, "tiles=${usStr(t1-t0)} widget=${usStr(t2-t1)}" +
            (if (!skipLiveUpdate) " liveUpdate=${usStr(t3-t2)}" else " liveUpdate=SKIP") +
            " total=${usStr(t3-t0)}")
    }

    private fun usStr(nanos: Long): String =
        if (nanos >= 1_000_000) String.format("%.2fms", nanos / 1_000_000.0)
        else String.format("%.1fµs", nanos / 1_000.0)
}

