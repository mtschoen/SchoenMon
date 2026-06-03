package com.sticktoitive.schoenmon.core

import java.util.Locale

/**
 * Shared formatting helpers so every glanceable surface (notification, Quick
 * Settings tiles, home/lock widget, Now Bar live update) renders identical
 * strings from the same [PerformanceStats].
 */
object StatFormat {

    fun cpuPercent(stats: PerformanceStats): Int =
        stats.avgCpuFrequencyPercent.toInt().coerceIn(0, 100)

    fun cpuLabel(stats: PerformanceStats): String =
        String.format(Locale.US, "%d%%", cpuPercent(stats))

    fun ramUsedGb(stats: PerformanceStats): Float =
        stats.usedMemoryBytes / 1024f / 1024f / 1024f

    fun ramTotalGb(stats: PerformanceStats): Float =
        stats.totalMemoryBytes / 1024f / 1024f / 1024f

    fun ramPercent(stats: PerformanceStats): Int {
        if (stats.totalMemoryBytes <= 0L) return 0
        return ((stats.usedMemoryBytes.toFloat() / stats.totalMemoryBytes.toFloat()) * 100f)
            .toInt().coerceIn(0, 100)
    }

    fun ramLabel(stats: PerformanceStats): String =
        String.format(Locale.US, "%.1f/%.1fG", ramUsedGb(stats), ramTotalGb(stats))

    fun speed(bytesPerSec: Long): String = when {
        bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1fM", bytesPerSec / 1024f / 1024f)
        bytesPerSec >= 1024 -> String.format(Locale.US, "%.0fK", bytesPerSec / 1024f)
        else -> "${bytesPerSec}B"
    }

    fun netLabel(stats: PerformanceStats): String =
        "↓${speed(stats.rxSpeedBytesPerSec)} ↑${speed(stats.txSpeedBytesPerSec)}"
}
