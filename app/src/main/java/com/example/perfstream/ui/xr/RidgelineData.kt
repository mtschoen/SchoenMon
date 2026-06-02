package com.example.perfstream.ui.xr

import com.example.perfstream.core.PerformanceStats

/**
 * Build the ridgeline lanes for the "Unknown Pleasures" surface.
 *
 * One lane per CPU core: each lane is that core's load fraction (`cur / max`,
 * clamped to `[0, 1]`) across the rolling history, oldest sample first. When
 * per-core frequencies are unavailable (the XR / emulator sysfs restriction),
 * fall back to four lanes derived from the aggregate CPU / RAM / NET / Disk
 * signal so the surface still pulses instead of going flat.
 *
 * Pure data shaping - no XR or Compose dependency - so it is unit-testable on
 * the JVM and independent of the chosen rendering technique.
 */
fun ridgelineLanes(history: List<PerformanceStats>, coreCount: Int): List<List<Float>> {
    if (coreCount <= 0) return fallbackLanes(history)
    return (0 until coreCount).map { core ->
        history.map { sample ->
            val current = sample.cpuCoreFrequencies.getOrNull(core) ?: 0L
            val maximum = sample.cpuMaxFreqs.getOrNull(core) ?: 0L
            if (maximum > 0L) (current.toFloat() / maximum.toFloat()).coerceIn(0f, 1f) else 0f
        }
    }
}

private fun fallbackLanes(history: List<PerformanceStats>): List<List<Float>> {
    fun lane(select: (PerformanceStats) -> Float) = history.map { select(it).coerceIn(0f, 1f) }
    return listOf(
        lane { it.avgCpuFrequencyPercent / 100f },
        lane { if (it.totalMemoryBytes > 0L) it.usedMemoryBytes.toFloat() / it.totalMemoryBytes else 0f },
        lane { it.rxSpeedBytesPerSec / (10f * 1024 * 1024) }, // 10 MB/s full-scale
        lane { if (it.totalDiskBytes > 0L) it.usedDiskBytes.toFloat() / it.totalDiskBytes else 0f },
    )
}
