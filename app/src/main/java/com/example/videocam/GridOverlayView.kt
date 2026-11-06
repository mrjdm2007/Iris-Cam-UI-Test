package com.example.videocam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Path
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Composition grids, plus letterbox bars that show the frame of a cinematic-ratio resolution drawn over the preview. Not clickable. */
class GridOverlayView(ctx: Context) : View(ctx) {

    companion object {
        val GRID_NAMES = listOf("Off", "Thirds", "Golden ratio", "Golden spiral", "Center cross", "Diagonals", "4\u00D74", "Fibonacci")
                private const val PHI = 1.618034
    }

    var grid = 0
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    var ratio = 0
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    var flip = 0
        set(v) {
            field = v
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEEFFFFFF.toInt()
        strokeWidth = 0.8f * density
        style = Paint.Style.STROKE
    }
    private val lineShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA000000.toInt()
        strokeWidth = 1.8f * density
        style = Paint.Style.STROKE
    }
    private val spiralShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA000000.toInt()
        strokeWidth = 2.2f * density
        style = Paint.Style.STROKE
    }
    private val spiral = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB300.toInt()
        strokeWidth = 1.1f * density
        style = Paint.Style.STROKE
    }
    private val bar = Paint().apply { color = 0xCC000000.toInt() }
    private val edge = Paint().apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 1f * density
    }

    private fun seg(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        c.drawLine(x1, y1, x2, y2, lineShadow)
        c.drawLine(x1, y1, x2, y2, line)
    }

    private fun box(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        c.drawRect(l, t, r, b, lineShadow)
        c.drawRect(l, t, r, b, line)
    }

    private fun vLine(c: Canvas, f: Float, w: Float, h: Float) = seg(c, w * f, 0f, w * f, h)
    private fun hLine(c: Canvas, f: Float, w: Float, h: Float) = seg(c, 0f, h * f, w, h * f)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        when (grid) {
            1 -> {
                vLine(c, 1 / 3f, w, h); vLine(c, 2 / 3f, w, h)
                hLine(c, 1 / 3f, w, h); hLine(c, 2 / 3f, w, h)
            }
            2, 3 -> {
                vLine(c, 0.382f, w, h); vLine(c, 0.618f, w, h)
                hLine(c, 0.382f, w, h); hLine(c, 0.618f, w, h)
                if (grid == 3) drawSpiral(c, w, h)
            }
            4 -> {
                seg(c, w / 2, h * 0.4f, w / 2, h * 0.6f)
                seg(c, w * 0.45f, h / 2, w * 0.55f, h / 2)
            }
            5 -> {
                seg(c, 0f, 0f, w, h)
                seg(c, w, 0f, 0f, h)
            }
            7 -> drawFibonacci(c, w, h, arForFrame(w, h))
            6 -> {
                for (i in 1..3) {
                    vLine(c, i / 4f, w, h)
                    hLine(c, i / 4f, w, h)
                }
            }
        }

        val ar = Ratios.VALUES.getOrElse(ratio) { 0f }
        if (ar > 0f) {
            val frameAr = w / h
            if (ar > frameAr) {
                val visH = w / ar
                val b = (h - visH) / 2f
                c.drawRect(0f, 0f, w, b, bar)
                c.drawRect(0f, h - b, w, h, bar)
                c.drawLine(0f, b, w, b, edge)
                c.drawLine(0f, h - b, w, h - b, edge)
            } else {
                val visW = h * ar
                val b = (w - visW) / 2f
                c.drawRect(0f, 0f, b, h, bar)
                c.drawRect(w - b, 0f, w, h, bar)
                c.drawLine(b, 0f, b, h, edge)
                c.drawLine(w - b, 0f, w - b, h, edge)
            }
        }
    }

    private fun arForFrame(w: Float, h: Float): RectF {
        val requested = Ratios.VALUES.getOrElse(ratio) { 0f }
        if (requested <= 0f) return RectF(0f, 0f, w, h)
        val viewAr = w / h
        return if (requested > viewAr) {
            val fh = w / requested
            val top = (h - fh) / 2f
            RectF(0f, top, w, top + fh)
        } else {
            val fw = h * requested
            val left = (w - fw) / 2f
            RectF(left, 0f, left + fw, h)
        }
    }

    /**
     * Draw a logarithmic golden spiral inside a fitted golden rectangle. The old
     * implementation used one small circular radius constrained by the shortest
     * distance to the frame edges, so most of the active image was left unused.
     */
    private fun drawFibonacci(c: Canvas, w: Float, h: Float, frame: RectF) {
        val fw = frame.width()
        val fh = frame.height()
        if (fw <= 0f || fh <= 0f) return

        // Fit a true phi rectangle inside the selected capture frame without crossing
        // the letterbox/crop boundary. This keeps the composition guide proportional.
        val goldenFrame = if (fw / fh >= PHI.toFloat()) {
            val gw = fh * PHI.toFloat()
            RectF(frame.centerX() - gw / 2f, frame.top, frame.centerX() + gw / 2f, frame.bottom)
        } else {
            val gh = fw / PHI.toFloat()
            RectF(frame.left, frame.centerY() - gh / 2f, frame.right, frame.centerY() + gh / 2f)
        }

        c.save()
        c.clipRect(frame)
        c.rotate(flip.coerceIn(0, 3) * 90f, frame.centerX(), frame.centerY())

        val gw = goldenFrame.width()
        val gh = goldenFrame.height()
        val x1 = goldenFrame.left + gw * 0.382f
        val x2 = goldenFrame.left + gw * 0.618f
        val y1 = goldenFrame.top + gh * 0.382f
        val y2 = goldenFrame.top + gh * 0.618f
        seg(c, x1, goldenFrame.top, x1, goldenFrame.bottom)
        seg(c, x2, goldenFrame.top, x2, goldenFrame.bottom)
        seg(c, goldenFrame.left, y1, goldenFrame.right, y1)
        seg(c, goldenFrame.left, y2, goldenFrame.right, y2)
        box(c, goldenFrame.left, goldenFrame.top, goldenFrame.right, goldenFrame.bottom)

        // Radius grows by phi every quarter-turn: r(theta) = exp(k * theta).
        // Normalize the sampled curve's bounds to the fitted rectangle so the entire
        // spiral is visible and uses the available composition area.
        val end = 6.0 * PI
        val growth = kotlin.math.ln(PHI) / (PI / 2.0)
        val points = ArrayList<Pair<Float, Float>>(481)
        var minX = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        var a = 0.0
        while (a <= end + 0.0001) {
            val r = kotlin.math.exp(growth * (a - end))
            val x = r * cos(a)
            val y = r * sin(a)
            points.add(x.toFloat() to y.toFloat())
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
            a += 0.04
        }
        val insetX = gw * 0.025f
        val insetY = gh * 0.025f
        val scaleX = (gw - 2f * insetX) / (maxX - minX).toFloat().coerceAtLeast(0.001f)
        val scaleY = (gh - 2f * insetY) / (maxY - minY).toFloat().coerceAtLeast(0.001f)
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = goldenFrame.left + insetX + (point.first - minX.toFloat()) * scaleX
            val y = goldenFrame.top + insetY + (point.second - minY.toFloat()) * scaleY
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, spiralShadow)
        c.drawPath(path, spiral)
        c.restore()
    }

    /** Logarithmic golden spiral winding clockwise in toward the lower-right phi intersection. */
    private fun drawSpiral(c: Canvas, w: Float, h: Float) {
        val ex = 0.618f * w
        val ey = 0.618f * h
        val r0 = min(ex * 0.95, ey * PHI * 0.98)
        val path = Path()
        var a = PI
        var first = true
        val end = PI + 6 * (PI / 2)
        while (a < end) {
            val r = r0 * PHI.pow(-((a - PI) / (PI / 2)))
            val x = (ex + r * cos(a)).toFloat()
            val y = (ey + r * sin(a)).toFloat()
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
            a += 0.04
        }
        c.drawPath(path, spiralShadow)
        c.drawPath(path, spiral)
    }
}
