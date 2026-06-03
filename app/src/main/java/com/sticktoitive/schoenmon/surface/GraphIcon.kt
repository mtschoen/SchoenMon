package com.sticktoitive.schoenmon.surface

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.sticktoitive.schoenmon.core.PerformanceStats
import com.sticktoitive.schoenmon.core.StatFormat

/**
 * Renders CPU and RAM history as two overlaid sparkline graphs. CPU is Cyber
 * Cyan, RAM is Electric Pink; each line has a gradient fill beneath it, and the
 * shorter of the two values is drawn last so it stays visible on top.
 *
 * Newest sample is at the right edge, so the graph scrolls left over time.
 * Used in the home/lock-screen widget where there is real estate for a trend -
 * NOT in the status-bar icon, where a shrunk sparkline is illegible (bars are
 * used there instead, see [BarsIcon]).
 *
 * ZERO per-frame allocations after first call: all Paint, Path, Shader, and
 * series arrays are pre-allocated and reused via reset()/rewind().
 */
object GraphIcon {

    private const val MAX_POINTS = 40

    // Pooled bitmap + canvas for the widget's fixed dimensions (480×120).
    private const val POOL_W = 480
    private const val POOL_H = 120
    private val pooledBitmap = Bitmap.createBitmap(POOL_W, POOL_H, Bitmap.Config.ARGB_8888)
    private val pooledCanvas = Canvas(pooledBitmap)

    private val cpuColor = Color.parseColor("#00E5FF")
    private val ramColor = Color.parseColor("#D500F9")

    // ── Pre-allocated drawing objects (ZERO per-frame allocs) ──

    // Line paints: one per series colour, created once.
    private val cpuLinePaint = makeLinePaint(cpuColor)
    private val ramLinePaint = makeLinePaint(ramColor)

    // Fill paints: one per series colour, shader set lazily when height changes.
    private val cpuFillPaint = makeFillPaint()
    private val ramFillPaint = makeFillPaint()

    // Cached shader parameters — only rebuild the LinearGradient if the height
    // (and therefore the gradient endpoints) actually change.
    private var cachedShaderHeight = -1
    private var cpuShader: LinearGradient? = null
    private var ramShader: LinearGradient? = null

    // Reusable Path objects: reset() is O(1) vs new Path() which does a JNI alloc.
    private val linePath = Path()
    private val fillPath = Path()

    // Pre-allocated series arrays: avoids .map{} boxing + list allocation per frame.
    private val cpuArr = IntArray(MAX_POINTS)
    private val ramArr = IntArray(MAX_POINTS)

    private fun makeLinePaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        this.color = color
    }

    private fun makeFillPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /**
     * Render the CPU+RAM history graph to a bitmap of the given pixel size.
     * The smaller of the two latest values is drawn last (on top) so a bar that
     * sits under the other is never fully hidden.
     */
    fun forHistory(history: List<PerformanceStats>, width: Int, height: Int): Bitmap {
        // Use pooled bitmap when dimensions match (the common case), else allocate.
        val (bitmap, canvas) = if (width == POOL_W && height == POOL_H) {
            pooledBitmap.eraseColor(Color.TRANSPARENT)
            pooledBitmap to pooledCanvas
        } else {
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp to Canvas(bmp)
        }

        // Fill pre-allocated int arrays instead of .map{} which boxes + allocates a List.
        val size = history.size
        val start = (size - MAX_POINTS).coerceAtLeast(0)
        val count = (size - start).coerceAtMost(MAX_POINTS)
        for (i in 0 until count) {
            val s = history[start + i]
            cpuArr[i] = StatFormat.cpuPercent(s)
            ramArr[i] = StatFormat.ramPercent(s)
        }

        // Rebuild shaders only when height changes (basically never after first call).
        ensureShaders(height)

        // Draw the series with the larger latest value first, so the smaller one
        // overlays on top and stays readable.
        val cpuLatest = if (count > 0) cpuArr[count - 1] else 0
        val ramLatest = if (count > 0) ramArr[count - 1] else 0
        if (cpuLatest >= ramLatest) {
            drawSeries(canvas, cpuArr, count, cpuLinePaint, cpuFillPaint, width, height)
            drawSeries(canvas, ramArr, count, ramLinePaint, ramFillPaint, width, height)
        } else {
            drawSeries(canvas, ramArr, count, ramLinePaint, ramFillPaint, width, height)
            drawSeries(canvas, cpuArr, count, cpuLinePaint, cpuFillPaint, width, height)
        }

        return bitmap
    }

    private fun ensureShaders(height: Int) {
        if (height == cachedShaderHeight) return
        cachedShaderHeight = height
        val top = 6f
        val bottom = height - 6f
        cpuShader = LinearGradient(
            0f, top, 0f, bottom,
            (cpuColor and 0x00FFFFFF) or 0x73000000,
            cpuColor and 0x00FFFFFF,
            Shader.TileMode.CLAMP
        )
        ramShader = LinearGradient(
            0f, top, 0f, bottom,
            (ramColor and 0x00FFFFFF) or 0x73000000,
            ramColor and 0x00FFFFFF,
            Shader.TileMode.CLAMP
        )
        cpuFillPaint.shader = cpuShader
        ramFillPaint.shader = ramShader
    }

    private fun drawSeries(
        canvas: Canvas,
        series: IntArray,
        count: Int,
        lineP: Paint,
        fillP: Paint,
        width: Int,
        height: Int,
    ) {
        if (count == 0) return

        val top = 6f
        val bottom = height - 6f
        val left = 4f
        val right = width - 4f
        val usableHeight = bottom - top
        val xStep = if (count > 1) (right - left) / (count - 1) else 0f

        // Build line path (reuse — reset is O(1), no JNI alloc).
        linePath.rewind()
        val y0 = bottom - usableHeight * (series[0].coerceIn(0, 100) / 100f)
        linePath.moveTo(if (count == 1) right else left, y0)
        for (i in 1 until count) {
            linePath.lineTo(left + xStep * i, bottom - usableHeight * (series[i].coerceIn(0, 100) / 100f))
        }

        // Build fill path: copy the line, close along the bottom edge.
        // Path.set() copies without allocating a new native path.
        fillPath.rewind()
        fillPath.addPath(linePath)
        val lastX = if (count == 1) right else left + xStep * (count - 1)
        fillPath.lineTo(lastX, bottom)
        fillPath.lineTo(if (count == 1) right else left, bottom)
        fillPath.close()

        canvas.drawPath(fillPath, fillP)
        canvas.drawPath(linePath, lineP)
    }
}
