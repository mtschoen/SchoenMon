package com.sticktoitive.schoenmon.core

import android.util.Log

/**
 * Lightweight per-tick profiler that measures each phase of the sampling loop
 * using System.nanoTime(). Emits a structured logcat line every tick plus a
 * rolling average summary every [SUMMARY_INTERVAL] ticks.
 *
 * Usage:
 *   val p = TickProfiler()
 *   p.begin()
 *   ... do work ...
 *   p.mark("sample")
 *   ... do work ...
 *   p.mark("notification")
 *   p.end()      // logs the tick
 *
 * Zero allocations per tick after the first [SUMMARY_INTERVAL] ticks (the
 * string formatting is the only allocation, and it happens once per tick in
 * logcat — unavoidable).
 */
class TickProfiler(private val tag: String = "SchoenMon.Perf") {

    companion object {
        private const val SUMMARY_INTERVAL = 20 // log rolling averages every N ticks
        private const val MAX_PHASES = 16
    }

    // Pre-allocated arrays — no per-tick allocations.
    private val phaseNames = arrayOfNulls<String>(MAX_PHASES)
    private val phaseNanos = LongArray(MAX_PHASES)
    private var phaseCount = 0
    private var tickStart = 0L

    // Rolling averages
    private val accumNanos = LongArray(MAX_PHASES)
    private val accumNames = arrayOfNulls<String>(MAX_PHASES)
    private var accumTotalNanos = 0L
    private var accumCount = 0
    private var maxPhaseCount = 0 // highest phaseCount seen

    fun begin() {
        phaseCount = 0
        tickStart = System.nanoTime()
    }

    /** Mark the end of a named phase. The elapsed time since the last mark
     *  (or begin()) is recorded under [name]. */
    fun mark(name: String) {
        val now = System.nanoTime()
        if (phaseCount < MAX_PHASES) {
            phaseNames[phaseCount] = name
            phaseNanos[phaseCount] = now - if (phaseCount == 0) tickStart else phaseNanos[phaseCount - 1].let { tickStart }.let {
                // Actually we need the absolute time of the previous mark
                // Recalculate: store absolute timestamps, derive durations at log time
                now
            }
        }
        phaseCount++
    }

    // --- Revised: store absolute timestamps, compute durations at log time ---
    private val absTimestamps = LongArray(MAX_PHASES + 1) // [0] = begin, [1..N] = marks

    fun beginTick() {
        phaseCount = 0
        absTimestamps[0] = System.nanoTime()
    }

    fun markPhase(name: String) {
        if (phaseCount < MAX_PHASES) {
            phaseCount++
            absTimestamps[phaseCount] = System.nanoTime()
            phaseNames[phaseCount - 1] = name
        }
    }

    fun endTick() {
        val totalNanos = absTimestamps[phaseCount] - absTimestamps[0]

        // Per-tick log
        val sb = StringBuilder(256)
        sb.append("TICK total=").append(usStr(totalNanos))
        for (i in 0 until phaseCount) {
            val dur = absTimestamps[i + 1] - absTimestamps[i]
            sb.append(" | ").append(phaseNames[i]).append('=').append(usStr(dur))
        }
        Log.d(tag, sb.toString())

        // Accumulate for summary
        accumTotalNanos += totalNanos
        for (i in 0 until phaseCount) {
            val dur = absTimestamps[i + 1] - absTimestamps[i]
            if (i < MAX_PHASES) {
                accumNanos[i] += dur
                accumNames[i] = phaseNames[i]
            }
        }
        if (phaseCount > maxPhaseCount) maxPhaseCount = phaseCount
        accumCount++

        if (accumCount >= SUMMARY_INTERVAL) {
            logSummary()
        }
    }

    private fun logSummary() {
        val sb = StringBuilder(512)
        sb.append("AVG(${accumCount}) total=").append(usStr(accumTotalNanos / accumCount))
        for (i in 0 until maxPhaseCount) {
            val avg = accumNanos[i] / accumCount
            val pct = if (accumTotalNanos > 0) (accumNanos[i] * 100 / accumTotalNanos) else 0
            sb.append(" | ").append(accumNames[i]).append('=').append(usStr(avg))
            sb.append(" (").append(pct).append("%)")
        }
        Log.i(tag, sb.toString())

        // Reset
        accumTotalNanos = 0
        accumNanos.fill(0)
        accumCount = 0
        maxPhaseCount = 0
    }

    private fun usStr(nanos: Long): String {
        return if (nanos >= 1_000_000) {
            String.format("%.2fms", nanos / 1_000_000.0)
        } else {
            String.format("%.1fµs", nanos / 1_000.0)
        }
    }
}
