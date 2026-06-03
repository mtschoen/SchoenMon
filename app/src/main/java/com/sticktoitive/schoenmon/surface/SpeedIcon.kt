package com.sticktoitive.schoenmon.surface

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
 * Technique mirrors the open-source NetSpeed Indicator: an ARGB_8888 bitmap,
 * number in a large condensed-bold face, unit smaller beneath, both centered,
 * wrapped via Icon.createWithBitmap(). 48×48 is the xhdpi (2×) icon size for
 * the 24dp status-bar slot; the system upscales to xxhdpi/xxxhdpi if needed,
 * and the text glyphs are simple enough that bilinear upscale is indiscernible.
 * Half the pixel count of the old 96×96 = 4× less work for eraseColor + Skia.
 */
object SpeedIcon {

    private const val SIZE = 48  // 24dp × 2 (xhdpi); was 96

    // Pooled bitmap + canvas.
    private val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    // Neo Green = network, matching the dashboard's bandwidth accent.
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E676")
        textSize = 29f   // scaled from 58f at 96px → 29f at 48px
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9DFFC9")
        textSize = 19f   // scaled from 38f → 19f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    /**
     * Build a status-bar icon for the given download rate (the more interesting
     * of the two for an at-a-glance meter). Number on top, unit beneath.
     */
    fun forSpeed(bytesPerSec: Long): Icon {
        val (number, unit) = splitSpeed(bytesPerSec)
        bitmap.eraseColor(Color.TRANSPARENT)
        canvas.drawText(number, 24f, 25f, numberPaint)   // centred at half-size
        canvas.drawText(unit, 24f, 46f, unitPaint)
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
