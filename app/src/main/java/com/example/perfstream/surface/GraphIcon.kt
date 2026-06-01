package com.example.perfstream.surface

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.Icon
import com.example.perfstream.core.PerformanceStats
import com.example.perfstream.core.StatFormat

/**
 * Renders CPU and RAM history as two overlaid sparkline graphs inside a
 * status-bar bitmap icon. Bitmap (not vector/resource) so the colors survive
 * the Samsung One UI status bar - confirmed on One UI 8.5. CPU is Cyber Cyan,
 * RAM is Electric Pink; each line has a faint gradient fill beneath it.
 *
 * Newest sample is at the right edge, so the graph scrolls left over time.
 */
object GraphIcon {

    private const val SIZE = 96
    private const val MAX_POINTS = 24 // enough to read a trend in a tiny icon

    private val cpuLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#00E5FF")
    }
    private val ramLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#D500F9")
    }

    /**
     * Build the graph icon from sample history. Falls back to flat lines at the
     * latest value if there is not yet enough history to draw a trend.
     */
    fun forHistory(history: List<PerformanceStats>): Icon {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val recent = history.takeLast(MAX_POINTS)
        val cpuSeries = recent.map { StatFormat.cpuPercent(it) }
        val ramSeries = recent.map { StatFormat.ramPercent(it) }

        drawSeries(canvas, ramSeries, ramLine, Color.parseColor("#D500F9"))
        drawSeries(canvas, cpuSeries, cpuLine, Color.parseColor("#00E5FF"))

        return Icon.createWithBitmap(bitmap)
    }

    private fun drawSeries(canvas: Canvas, series: List<Int>, linePaint: Paint, lineColor: Int) {
        if (series.isEmpty()) return

        val top = 10f
        val bottom = SIZE - 10f
        val left = 6f
        val right = SIZE - 6f
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

        // Gradient fill under the line for a premium look.
        val fillPath = Path(linePath)
        fillPath.lineTo(xAt(series.size - 1), bottom)
        fillPath.lineTo(xAt(0), bottom)
        fillPath.close()
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, top, 0f, bottom,
                (lineColor and 0x00FFFFFF) or 0x66000000, // ~40% alpha at top
                lineColor and 0x00FFFFFF,                 // transparent at bottom
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
    }
}
