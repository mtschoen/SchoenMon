package com.example.perfstream.ui.xr

import com.example.perfstream.core.PerformanceStats
import org.junit.Assert.assertEquals
import org.junit.Test

class RidgelineDataTest {
    private fun stat(cur: List<Long>, max: List<Long>) = PerformanceStats(
        cpuCoreFrequencies = cur, cpuMaxFreqs = max,
        rxSpeedBytesPerSec = 0, txSpeedBytesPerSec = 0,
        totalMemoryBytes = 0, availableMemoryBytes = 0,
        totalDiskBytes = 0, availableDiskBytes = 0,
    )

    @Test
    fun perCoreLoadFraction_isCurOverMax_clampedToUnit() {
        val history = listOf(
            stat(cur = listOf(1000L, 500L), max = listOf(2000L, 2000L)),
            stat(cur = listOf(2000L, 0L), max = listOf(2000L, 2000L)),
        )
        val lanes = ridgelineLanes(history, coreCount = 2)
        // lane 0 = core 0 across both samples; lane 1 = core 1.
        assertEquals(listOf(0.5f, 1.0f), lanes[0])
        assertEquals(listOf(0.25f, 0.0f), lanes[1])
    }

    @Test
    fun fallsBackToFourChannels_whenNoPerCoreData() {
        val history = listOf(stat(cur = emptyList(), max = emptyList()))
        val lanes = ridgelineLanes(history, coreCount = 0)
        // 4 fallback lanes: CPU avg / RAM / NET / Disk - all present, never empty.
        assertEquals(4, lanes.size)
    }
}
