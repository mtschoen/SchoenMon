package com.example.perfstream.surface

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.perfstream.R
import com.example.perfstream.core.PerformanceStats
import com.example.perfstream.core.StatFormat
import com.example.perfstream.data.PerformanceMonitorRepository

/**
 * Base for the live Quick Settings tiles. Each concrete tile is an "active"
 * tile: the foreground service calls [requestUpdate] every sample tick, which
 * asks the system to bind us and call [onStartListening], where we pull the
 * latest sample from the shared repository and push it to the tile label.
 *
 * Caveat (researched): a QS tile only repaints while the shade is open, and
 * active tiles are limited to one update per listen cycle. So this is a
 * "pull-the-shade-to-glance" surface, not an always-visible meter. That is the
 * documented ceiling of the API, not a bug.
 */
abstract class PerfTileService : TileService() {

    /** Pull the metric-specific label out of the latest sample. */
    protected abstract fun label(stats: PerformanceStats): String

    protected abstract fun tileIcon(): Int

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        // Tapping refreshes immediately; the dashboard is one pull away.
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val stats = PerformanceMonitorRepository.stats.value
        tile.icon = Icon.createWithResource(this, tileIcon())
        if (stats == null) {
            tile.label = shortName()
            tile.subtitle = "--"
            tile.state = Tile.STATE_INACTIVE
        } else {
            tile.label = shortName()
            tile.subtitle = label(stats)
            tile.state = Tile.STATE_ACTIVE
        }
        tile.updateTile()
    }

    protected abstract fun shortName(): String

    companion object {
        /** Ask the system to relist all three tiles with fresh data. */
        fun requestUpdate(context: Context) {
            for (cls in listOf(
                CpuTileService::class.java,
                RamTileService::class.java,
                NetTileService::class.java,
            )) {
                runCatching {
                    requestListeningState(context, ComponentName(context, cls))
                }
            }
        }
    }
}

class CpuTileService : PerfTileService() {
    override fun shortName() = "CPU"
    override fun tileIcon() = R.drawable.ic_tile_cpu
    override fun label(stats: PerformanceStats) = StatFormat.cpuLabel(stats)
}

class RamTileService : PerfTileService() {
    override fun shortName() = "RAM"
    override fun tileIcon() = R.drawable.ic_tile_ram
    override fun label(stats: PerformanceStats) = StatFormat.ramLabel(stats)
}

class NetTileService : PerfTileService() {
    override fun shortName() = "Net"
    override fun tileIcon() = R.drawable.ic_tile_net
    override fun label(stats: PerformanceStats) = StatFormat.netLabel(stats)
}
