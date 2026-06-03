package com.sticktoitive.schoenmon.surface

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon

/**
 * Colored CPU / RAM bar-chart status-bar icon, rendered as a bitmap so it keeps
 * its colors in the Samsung One UI status bar (resource/vector icons get
 * force-tinted monochrome; bitmaps do not - confirmed on One UI 8.5). Two
 * vertical bars filled from the bottom proportional to load over a faint track:
 * CPU in Cyber Cyan, RAM in Electric Pink.
 *
 * Chosen over a sparkline graph because a 96x96 icon shrunk into the ~24px
 * status-bar slot has no room for a legible trend line - two filled bars read
 * at a glance, a graph becomes a smudge.
 */
object BarsIcon {

    private const val SIZE = 96

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
    }
    private val cpuPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Cyber Cyan = CPU
    }
    private val ramPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D500F9") // Electric Pink = RAM
    }

    /** Build a two-bar icon for the given CPU and RAM percentages (0-100). */
    fun forLoads(cpuPercent: Int, ramPercent: Int): Icon {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val top = 8f
        val bottom = SIZE - 8f
        val height = bottom - top
        val barWidth = 32f
        val gap = 10f
        val totalWidth = barWidth * 2 + gap
        val startX = (SIZE - totalWidth) / 2f

        drawBar(canvas, startX, top, bottom, barWidth, height, cpuPercent, cpuPaint)
        drawBar(canvas, startX + barWidth + gap, top, bottom, barWidth, height, ramPercent, ramPaint)

        return Icon.createWithBitmap(bitmap)
    }

    private fun drawBar(
        canvas: Canvas,
        x: Float,
        top: Float,
        bottom: Float,
        width: Float,
        height: Float,
        percent: Int,
        fillPaint: Paint,
    ) {
        val radius = 7f
        // Faint full-height track so an empty bar is still visible.
        canvas.drawRoundRect(RectF(x, top, x + width, bottom), radius, radius, trackPaint)
        // Filled portion from the bottom up.
        val fillTop = bottom - height * (percent.coerceIn(0, 100) / 100f)
        canvas.drawRoundRect(RectF(x, fillTop, x + width, bottom), radius, radius, fillPaint)
    }
}
