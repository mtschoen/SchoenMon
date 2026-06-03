package com.sticktoitive.schoenmon.surface

import android.content.Context
import com.sticktoitive.schoenmon.core.PerformanceStats

/**
 * Single fan-out point the foreground service calls every sample tick. Keeps
 * the service ignorant of how many glanceable surfaces exist; each surface
 * decides for itself whether it has anything to refresh.
 */
object PerfSurfaces {
    fun refreshAll(context: Context, stats: PerformanceStats) {
        // Quick Settings tiles (CPU / RAM / Net) - cheap, request a relisten.
        PerfTileService.requestUpdate(context)
        // Home / lock screen widget.
        PerfWidgetProvider.update(context, stats)
        // Now Bar / Android 16 Live Update.
        LiveUpdateController.update(context, stats)
    }
}
