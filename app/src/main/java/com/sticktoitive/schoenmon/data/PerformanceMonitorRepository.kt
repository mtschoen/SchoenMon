package com.sticktoitive.schoenmon.data

import com.sticktoitive.schoenmon.core.PerformanceStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object PerformanceMonitorRepository {
    private val _stats = MutableStateFlow<PerformanceStats?>(null)
    val stats: StateFlow<PerformanceStats?> = _stats

    private val _history = MutableStateFlow<List<PerformanceStats>>(emptyList())
    val history: StateFlow<List<PerformanceStats>> = _history

    // Ring buffer: ArrayDeque.removeFirst() is O(1), no array copy.
    // The old approach used toMutableList() + removeAt(0) which copied 60
    // elements every tick.
    private const val MAX_HISTORY = 60
    private val ring = ArrayDeque<PerformanceStats>(MAX_HISTORY + 1)

    /**
     * Updates the current stats and appends to the history buffer (capped at 60 entries).
     */
    fun updateStats(newStats: PerformanceStats) {
        _stats.value = newStats

        ring.addLast(newStats)
        if (ring.size > MAX_HISTORY) {
            ring.removeFirst()
        }
        _history.value = ring.toList()
    }
}

