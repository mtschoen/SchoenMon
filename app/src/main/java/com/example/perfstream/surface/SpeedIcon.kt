package com.example.perfstream.surface

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import java.util.Locale

/**
 * Renders the live network speed as a status-bar icon with the number stacked
 * over the unit (e.g. "1.1" over "M/s"), the way dedicated speed-meter apps do.
 *
 * Technique mirrors the open-source NetSpeed Indicator: a 96x96 ARGB_8888
 * bitmap, number in a large condensed-bold face, unit smaller beneath, both
 * centered, wrapped via Icon.createWithBitmap(). Our project's prior notes
 * claimed Samsung's AppIconSolution rewrites bitmap-backed icons to the
 * launcher icon - but shipping speed-meter apps prove otherwise, so this is
 * the path we test. If a given One UI build does robot it, fall back to the
 * static vector arrow (ic_stat_net_speed).
 */
object SpeedIcon {

    private const val SIZE = 96

    // Neo Green = network, matching the dashboard's bandwidth accent. A bitmap
    // icon keeps its own colors in the status bar (unlike vector/resource icons,
    // which the system force-tints monochrome), so this is how we get color back.
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        textSize = 58f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9DFFC9") // lighter green for the unit
        textSize = 38f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    /**
     * Build a status-bar icon for the given download rate (the more interesting
     * of the two for an at-a-glance meter). Number on top, unit beneath.
     */
    fun forSpeed(bytesPerSec: Long): Icon {
        val (number, unit) = splitSpeed(bytesPerSec)
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Number sits in the upper ~60%, unit in the lower band - baselines
        // tuned so the pair reads as one glyph inside the small icon slot.
        canvas.drawText(number, 48f, 50f, numberPaint)
        canvas.drawText(unit, 48f, 92f, unitPaint)
        return Icon.createWithBitmap(bitmap)
    }

    /** Split a byte rate into a short number string and a compact unit string. */
    private fun splitSpeed(bytesPerSec: Long): Pair<String, String> = when {
        bytesPerSec >= 1024 * 1024 ->
            shortNumber(bytesPerSec / 1024f / 1024f) to "M/s"
        bytesPerSec >= 1024 ->
            shortNumber(bytesPerSec / 1024f) to "K/s"
        else ->
            bytesPerSec.toString() to "B/s"
    }

    /** Keep the number to 3 glyphs max so it stays legible in the icon. */
    private fun shortNumber(value: Float): String = when {
        value >= 100f -> String.format(Locale.US, "%.0f", value)
        value >= 10f -> String.format(Locale.US, "%.0f", value)
        else -> String.format(Locale.US, "%.1f", value)
    }
}
