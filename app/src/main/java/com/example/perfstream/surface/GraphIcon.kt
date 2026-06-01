package com.example.perfstream.surface

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.example.perfstream.core.PerformanceStats
import com.example.perfstream.core.StatFormat

/**
 * Renders CPU and RAM history as two overlaid sparkline graphs. CPU is Cyber
 * Cyan, RAM is Electric Pink; each line has a gradient fill beneath it, and the
 * shorter of the two values is drawn last so it stays visible on top.
 *
 * Newest sample is at the right edge, so the graph scrolls left over time.
 * Used in the home/lock-screen widget where there is real estate for a trend -
 * NOT in the status-bar icon, where a shrunk sparkline is illegible (bars are
 * used there instead, see [BarsIcon]).
 */
object GraphIcon {

    private const val MAX_POINTS = 40

    private fun linePaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }

    private val cpuColor = Color.parseColor("#00E5FF")
    private val ramColor = Color.parseColor("#D500F9")

    /**
     * Render the CPU+RAM history graph to a bitmap of the given pixel size.
     * The smaller of the two latest values is drawn last (on top) so a bar that
     * sits under the other is never fully hidden.
     */
    fun forHistory(history: List<PerformanceStats>, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val recent = history.takeLast(MAX_POINTS)
        val cpuSeries = recent.map { StatFormat.cpuPercent(it) }
        val ramSeries = recent.map { StatFormat.ramPercent(it) }

        // Draw the series with the larger latest value first, so the smaller one
        // overlays on top and stays readable.
        val cpuLatest = cpuSeries.lastOrNull() ?: 0
        val ramLatest = ramSeries.lastOrNull() ?: 0
        if (cpuLatest >= ramLatest) {
            drawSeries(canvas, cpuSeries, cpuColor, width, height)
            drawSeries(canvas, ramSeries, ramColor, width, height)
        } else {
            drawSeries(canvas, ramSeries, ramColor, width, height)
            drawSeries(canvas, cpuSeries, cpuColor, width, height)
        }

        return bitmap
    }

    private fun drawSeries(
        canvas: Canvas,
        series: List<Int>,
        color: Int,
        width: Int,
        height: Int,
    ) {
        if (series.isEmpty()) return

        val top = 6f
        val bottom = height - 6f
        val left = 4f
        val right = width - 4f
        val usableHeight = bottom - top

        fun xAt(index: Int): Float {
            if (series.size == 1) return right
            return left + (right - left) * index / (series.size - 1)
        }
        fun yAt(value: Int): Float = bottom - usableHeight * (value.coerceIn(0, 100) / 100f)

        val linePath = Path()
        linePath.moveTo(xAt(0), yAt(series[0]))
        for (i in 1 until series.size) {
            linePath.lineTo(xAt(i), yAt(series[i]))
        }

        // Gradient fill under the line.
        val fillPath = Path(linePath)
        fillPath.lineTo(xAt(series.size - 1), bottom)
        fillPath.lineTo(xAt(0), bottom)
        fillPath.close()
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, top, 0f, bottom,
                (color and 0x00FFFFFF) or 0x73000000, // ~45% alpha at top
                color and 0x00FFFFFF,                 // transparent at bottom
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint(color))
    }
}
