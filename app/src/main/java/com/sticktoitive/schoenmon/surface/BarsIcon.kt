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
 * 48×48 ARGB_8888 (24dp × 2 for xhdpi). The system upscales cleanly for
 * xxhdpi/xxxhdpi; the simple rectangles don't suffer from bilinear scaling.
 * Half the pixel count of the old 96×96 = 4× less work for eraseColor + Skia.
 *
 * ZERO per-frame allocations: Paint objects, RectF, bitmap, and canvas are all
 * pre-allocated and reused.
 */
object BarsIcon {

    private const val SIZE = 48  // was 96

    // Pooled bitmap + canvas.
    private val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    // Pooled RectF: reused across all drawRoundRect calls.
    private val rect = RectF()

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
        bitmap.eraseColor(Color.TRANSPARENT)

        val top = 4f      // scaled from 8f
        val bottom = SIZE - 4f
        val height = bottom - top
        val barWidth = 16f // scaled from 32f
        val gap = 5f       // scaled from 10f
        val totalWidth = barWidth * 2 + gap
        val startX = (SIZE - totalWidth) / 2f

        drawBar(startX, top, bottom, barWidth, height, cpuPercent, cpuPaint)
        drawBar(startX + barWidth + gap, top, bottom, barWidth, height, ramPercent, ramPaint)

        return Icon.createWithBitmap(bitmap)
    }

    private fun drawBar(
        x: Float,
        top: Float,
        bottom: Float,
        width: Float,
        height: Float,
        percent: Int,
        fillPaint: Paint,
    ) {
        val radius = 3.5f  // scaled from 7f
        // Faint full-height track so an empty bar is still visible.
        rect.set(x, top, x + width, bottom)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)
        // Filled portion from the bottom up.
        val fillTop = bottom - height * (percent.coerceIn(0, 100) / 100f)
        rect.set(x, fillTop, x + width, bottom)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
    }
}
