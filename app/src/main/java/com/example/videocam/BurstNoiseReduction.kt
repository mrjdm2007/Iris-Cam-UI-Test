package com.example.videocam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Low-latency 3-frame burst denoiser for same-exposure JPEG frames.
 *
 * Uses a small luminance proxy to estimate translation, then a motion-robust
 * per-pixel median/mean blend. It deliberately avoids expensive per-pixel
 * exposure weighting and large spatial filters.
 */
object BurstNoiseReduction {
    fun merge(jpegs: List<ByteArray>): Bitmap? {
        if (jpegs.size < 2) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegs[0], 0, jpegs[0].size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        val sample = max(1, max(width, height) / 480)
        val thumbs = ArrayList<Bitmap>(jpegs.size)
        try {
            for (bytes in jpegs.take(3)) {
                val opt = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt) ?: return null
                if (bmp.width != thumbs.firstOrNull()?.width && thumbs.isNotEmpty()) {
                    bmp.recycle()
                    return null
                }
                thumbs.add(bmp)
            }
            val sw = thumbs[0].width
            val sh = thumbs[0].height
            val reference = IntArray(sw * sh)
            thumbs[0].getPixels(reference, 0, sw, 0, 0, sw, sh)
            val refY = IntArray(reference.size) { luma(reference[it]) }
            val shifts = Array(thumbs.size) { intArrayOf(0, 0) }
            for (i in 1 until thumbs.size) {
                val pixels = IntArray(sw * sh)
                thumbs[i].getPixels(pixels, 0, sw, 0, 0, sw, sh)
                val otherY = IntArray(pixels.size) { luma(pixels[it]) }
                shifts[i] = align(refY, otherY, sw, sh)
            }
            thumbs.forEach { it.recycle() }
            thumbs.clear()

            val full = ArrayList<Bitmap>(jpegs.size)
            for (bytes in jpegs.take(3)) {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
                    full.forEach { it.recycle() }
                    return null
                }
                if (bmp.width != width || bmp.height != height) {
                    bmp.recycle()
                    full.forEach { it.recycle() }
                    return null
                }
                full.add(bmp)
            }

            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val rows = Array(full.size) { IntArray(width) }
            val output = IntArray(width)
            val sx = width.toFloat() / sw
            val sy = height.toFloat() / sh
            val dx = IntArray(full.size) { (shifts[it][0] * sx).toInt() }
            val dy = IntArray(full.size) { (shifts[it][1] * sy).toInt() }

            for (y in 0 until height) {
                for (i in full.indices) {
                    full[i].getPixels(rows[i], 0, width, 0, (y + dy[i]).coerceIn(0, height - 1), width, 1)
                }
                for (x in 0 until width) {
                    val p0 = rows[0][x]
                    val p1 = rows[1][(x + dx[1]).coerceIn(0, width - 1)]
                    val p2 = rows[2.coerceAtMost(rows.lastIndex)][(x + dx[2.coerceAtMost(dx.lastIndex)]).coerceIn(0, width - 1)]
                    val y0 = luma(p0)
                    val y1 = luma(p1)
                    val y2 = luma(p2)
                    val spread = max(y0, max(y1, y2)) - min(y0, min(y1, y2))
                    output[x] = if (spread <= 18) {
                        average(p0, p1, p2)
                    } else {
                        // Choose the whole colour sample with median luminance to resist motion ghosts.
                        when {
                            (y0 >= y1 && y0 <= y2) || (y0 <= y1 && y0 >= y2) -> p0
                            (y1 >= y0 && y1 <= y2) || (y1 <= y0 && y1 >= y2) -> p1
                            else -> p2
                        } or (0xFF shl 24)
                    }
                }
                out.setPixels(output, 0, width, 0, y, width, 1)
            }
            full.forEach { it.recycle() }
            return out
        } finally {
            thumbs.forEach { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun luma(p: Int): Int =
        (77 * ((p shr 16) and 255) + 150 * ((p shr 8) and 255) + 29 * (p and 255)) shr 8

    private fun average(a: Int, b: Int, c: Int): Int {
        val r = ((((a shr 16) and 255) + ((b shr 16) and 255) + ((c shr 16) and 255)) / 3)
        val g = ((((a shr 8) and 255) + ((b shr 8) and 255) + ((c shr 8) and 255)) / 3)
        val bl = (((a and 255) + (b and 255) + (c and 255)) / 3)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** Finds the integer translation that best aligns other to reference. */
    private fun align(reference: IntArray, other: IntArray, width: Int, height: Int): IntArray {
        val radius = min(6, min(width, height) / 8)
        if (radius < 1) return intArrayOf(0, 0)
        var bestX = 0
        var bestY = 0
        var bestScore = Long.MAX_VALUE
        val step = max(1, min(width, height) / 160)
        for (dy in -radius..radius) for (dx in -radius..radius) {
            var score = 0L
            var count = 0
            var y = radius
            while (y < height - radius) {
                var x = radius
                while (x < width - radius) {
                    score += abs(reference[y * width + x] - other[(y + dy) * width + x + dx])
                    count++
                    x += step
                }
                y += step
            }
            if (count > 0 && score < bestScore) {
                bestScore = score
                bestX = dx
                bestY = dy
            }
        }
        return intArrayOf(bestX, bestY)
    }
}
