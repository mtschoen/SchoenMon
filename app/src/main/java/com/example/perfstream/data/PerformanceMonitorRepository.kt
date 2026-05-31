package com.example.perfstream.data

import com.example.perfstream.core.PerformanceStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object PerformanceMonitorRepository {
    private val _stats = MutableStateFlow<PerformanceStats?>(null)
    val stats: StateFlow<PerformanceStats?> = _stats

    private val _history = MutableStateFlow<List<PerformanceStats>>(emptyList())
    val history: StateFlow<List<PerformanceStats>> = _history

    /**
     * Updates the current stats and appends to the history buffer (capped at 60 entries).
     */
    fun updateStats(newStats: PerformanceStats) {
        _stats.value = newStats
        
        val currentHistory = _history.value.toMutableList()
        currentHistory.add(newStats)
        if (currentHistory.size > 60) {
            currentHistory.removeAt(0)
        }
        _history.value = currentHistory
    }
}
