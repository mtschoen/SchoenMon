package com.example.perfstream.core

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.Environment
import android.os.StatFs
import java.io.File

data class PerformanceStats(
    val cpuCoreFrequencies: List<Long>, // Current frequencies in MHz
    val cpuMaxFreqs: List<Long>,        // Max frequencies in MHz
    val rxSpeedBytesPerSec: Long,       // Download speed
    val txSpeedBytesPerSec: Long,       // Upload speed
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val totalDiskBytes: Long,
    val availableDiskBytes: Long
) {
    val usedMemoryBytes: Long get() = totalMemoryBytes - availableMemoryBytes
    val usedDiskBytes: Long get() = totalDiskBytes - availableDiskBytes
    
    // Helper to calculate total active percentage of CPU frequency
    val avgCpuFrequencyPercent: Float
        get() {
            if (cpuCoreFrequencies.isEmpty() || cpuMaxFreqs.isEmpty()) return 0f
            var sumCurrent = 0L
            var sumMax = 0L
            for (i in cpuCoreFrequencies.indices) {
                if (i < cpuMaxFreqs.size && cpuMaxFreqs[i] > 0) {
                    sumCurrent += cpuCoreFrequencies[i]
                    sumMax += cpuMaxFreqs[i]
                }
            }
            return if (sumMax > 0L) (sumCurrent.toFloat() / sumMax.toFloat()) * 100f else 0f
        }
}

class StatsCollector(private val context: Context) {

    private var prevRxBytes = 0L
    private var prevTxBytes = 0L
    private var prevTimeMs = 0L

    init {
        // Pre-initialize counters
        prevRxBytes = TrafficStats.getTotalRxBytes()
        prevTxBytes = TrafficStats.getTotalTxBytes()
        prevTimeMs = System.currentTimeMillis()
    }

    /**
     * Samples and compiles all performance statistics.
     */
    fun sample(): PerformanceStats {
        val cpuFreqs = getCpuFrequencies()
        val cpuMaxs = getCpuMaxFrequencies()
        val (rxSpeed, txSpeed) = sampleNetworkSpeed()
        val (totalMem, availMem) = getMemoryStats()
        val (totalDisk, availDisk) = getDiskStats()

        return PerformanceStats(
            cpuCoreFrequencies = cpuFreqs,
            cpuMaxFreqs = cpuMaxs,
            rxSpeedBytesPerSec = rxSpeed,
            txSpeedBytesPerSec = txSpeed,
            totalMemoryBytes = totalMem,
            availableMemoryBytes = availMem,
            totalDiskBytes = totalDisk,
            availableDiskBytes = availDisk
        )
    }

    private fun getCpuFrequencies(): List<Long> {
        val freqs = mutableListOf<Long>()
        try {
            val cpuDir = File("/sys/devices/system/cpu")
            val files = cpuDir.listFiles { file -> file.isDirectory && file.name.matches(Regex("cpu[0-9]+")) }
            if (files != null && files.isNotEmpty()) {
                val sortedFiles = files.sortedBy { it.name.substring(3).toIntOrNull() ?: 99 }
                for (file in sortedFiles) {
                    val freqFile = File(file, "cpufreq/scaling_cur_freq")
                    if (freqFile.exists() && freqFile.canRead()) {
                        val freqHz = freqFile.readText().trim().toLongOrNull() ?: 0L
                        freqs.add(freqHz / 1000) // Convert to MHz
                    } else {
                        freqs.add(0L) // Core may be sleeping/offline
                    }
                }
            } else {
                // Fallback for emulators or systems that restrict listing
                for (i in 0..7) {
                    val freqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                    if (freqFile.exists() && freqFile.canRead()) {
                        val freqHz = freqFile.readText().trim().toLongOrNull() ?: 0L
                        freqs.add(freqHz / 1000)
                    }
                }
            }
        } catch (e: Exception) {
            // Silence
        }
        return freqs
    }

    private fun getCpuMaxFrequencies(): List<Long> {
        val freqs = mutableListOf<Long>()
        try {
            val cpuDir = File("/sys/devices/system/cpu")
            val files = cpuDir.listFiles { file -> file.isDirectory && file.name.matches(Regex("cpu[0-9]+")) }
            if (files != null && files.isNotEmpty()) {
                val sortedFiles = files.sortedBy { it.name.substring(3).toIntOrNull() ?: 99 }
                for (file in sortedFiles) {
                    val freqFile = File(file, "cpufreq/scaling_max_freq")
                    if (freqFile.exists() && freqFile.canRead()) {
                        val freqHz = freqFile.readText().trim().toLongOrNull() ?: 0L
                        freqs.add(freqHz / 1000) // Convert to MHz
                    } else {
                        freqs.add(0L)
                    }
                }
            } else {
                for (i in 0..7) {
                    val freqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_max_freq")
                    if (freqFile.exists() && freqFile.canRead()) {
                        val freqHz = freqFile.readText().trim().toLongOrNull() ?: 0L
                        freqs.add(freqHz / 1000)
                    }
                }
            }
        } catch (e: Exception) {
            // Silence
        }
        return freqs
    }

    private fun sampleNetworkSpeed(): Pair<Long, Long> {
        val currRx = TrafficStats.getTotalRxBytes()
        val currTx = TrafficStats.getTotalTxBytes()
        val currTime = System.currentTimeMillis()

        // Handle error states or reset
        if (currRx == TrafficStats.UNSUPPORTED.toLong() || currTx == TrafficStats.UNSUPPORTED.toLong()) {
            return Pair(0L, 0L)
        }

        if (prevTimeMs == 0L || currRx < prevRxBytes || currTx < prevTxBytes) {
            prevRxBytes = currRx
            prevTxBytes = currTx
            prevTimeMs = currTime
            return Pair(0L, 0L)
        }

        val timeDiffMs = currTime - prevTimeMs
        if (timeDiffMs <= 0) return Pair(0L, 0L)

        // Speed in bytes/sec
        val rxSpeed = ((currRx - prevRxBytes) * 1000) / timeDiffMs
        val txSpeed = ((currTx - prevTxBytes) * 1000) / timeDiffMs

        prevRxBytes = currRx
        prevTxBytes = currTx
        prevTimeMs = currTime

        return Pair(rxSpeed, txSpeed)
    }

    private fun getMemoryStats(): Pair<Long, Long> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return Pair(memoryInfo.totalMem, memoryInfo.availMem)
    }

    private fun getDiskStats(): Pair<Long, Long> {
        return try {
            val path = Environment.getDataDirectory().path
            val statFs = StatFs(path)
            val totalBytes = statFs.blockCountLong * statFs.blockSizeLong
            val availableBytes = statFs.availableBlocksLong * statFs.blockSizeLong
            Pair(totalBytes, availableBytes)
        } catch (e: Exception) {
            Pair(0L, 0L)
        }
    }
}
