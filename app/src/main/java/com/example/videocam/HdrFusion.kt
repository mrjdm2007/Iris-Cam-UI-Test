package com.example.videocam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * HDR mode: blends 2 (dark + normal) or 3 (dark + normal + bright) frames of the same scene (exposure fusion) with
 * hand-shake alignment, moving-subject (ghost) rejection and a saturation-aware weighting. For Ultra HDR it also builds
 * a gain map (how much brighter each pixel really was in the darkest frame) so HDR screens can show the true highlights.
 */
object HdrFusion {
    class Result(val base: Bitmap, val gain: Bitmap?)

    const val MAX_RATIO = 6f

    private val LIN = FloatArray(256) { i ->
        val v = i / 255f
        if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }

    private fun lumOf(p: Int) = (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8

    /** [jpegs] in the order dark, normal[, bright]; [rel] = light of each frame relative to the normal one. */
    fun merge(jpegs: List<ByteArray>, rel: FloatArray?, wantGain: Boolean): Result? {
        val n = min(3, jpegs.size)
        if (n < 2) return null
        val ref = 1
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegs[ref], 0, jpegs[ref].size, bo)
        val fw = bo.outWidth
        val fh = bo.outHeight
        if (fw <= 0 || fh <= 0) return null
        var sample = 1
        while (max(fw, fh) / sample > 640) sample *= 2

        // ---- small copies: alignment, blend weights, gain map ----
        var sw = 0
        var sh = 0
        val px = ArrayList<IntArray>()
        for (j in jpegs.take(n)) {
            val o = BitmapFactory.Options()
            o.inSampleSize = sample
            val b = BitmapFactory.decodeByteArray(j, 0, j.size, o) ?: return null
            if (sw == 0) {
                sw = b.width
                sh = b.height
            } else if (b.width != sw || b.height != sh) {
                b.recycle()
                return null
            }
            val a = IntArray(sw * sh)
            b.getPixels(a, 0, sw, 0, 0, sw, sh)
            b.recycle()
            px.add(a)
        }
        val lum = Array(n) { k -> IntArray(sw * sh) { i -> lumOf(px[k][i]) } }
        val grads = Array(n) { grad(lum[it], sw, sh) }
        val shifts = Array(n) { k -> if (k == ref) intArrayOf(0, 0) else align(grads[ref], grads[k], sw, sh) }

        val wt = Array(n) { FloatArray(sw * sh) }
        for (k in 0 until n) {
            val dx = shifts[k][0]
            val dy = shifts[k][1]
            val l = lum[k]
            for (y in 0 until sh) {
                val yy = (y + dy).coerceIn(0, sh - 1)
                for (x in 0 until sw) {
                    val xx = (x + dx).coerceIn(0, sw - 1)
                    val i = yy * sw + xx
                    val v = l[i] / 255f
                    val we = exp(-((v - 0.5f) * (v - 0.5f)) / (2f * 0.2f * 0.2f))
                    val xl = (xx - 1).coerceAtLeast(0)
                    val xr = (xx + 1).coerceAtMost(sw - 1)
                    val yu = (yy - 1).coerceAtLeast(0)
                    val yd = (yy + 1).coerceAtMost(sh - 1)
                    val lap = abs(4f * l[i] - l[yy * sw + xl] - l[yy * sw + xr] - l[yu * sw + xx] - l[yd * sw + xx]) / 255f
                    val p = px[k][i]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val sat = (max(r, max(g, b)) - min(r, min(g, b))) / 255f
                    var w = we * (0.3f + 3f * lap) * (0.6f + sat) + 1e-4f
                    if (k != ref && rel != null && rel.size > k) {
                        // moving subject: after matching the exposure the two frames should agree; if not, trust the normal frame
                        val j = y * sw + x
                        val lr = lum[ref][j]
                        if (l[i] in 12..243 && lr in 12..243) {
                            val a1 = LIN[l[i]] / rel[k]
                            val a2 = LIN[lr]
                            if (abs(a1 - a2) / (a2 + 0.02f) > 0.55f) w *= 0.04f
                        }
                    }
                    wt[k][y * sw + x] = w
                }
            }
            wt[k] = box(wt[k], sw, sh, 1)
        }
        for (i in 0 until sw * sh) {
            var s = 0f
            for (k in 0 until n) s += wt[k][i]
            for (k in 0 until n) wt[k][i] /= s
        }

        // ---- gain map (small): how much brighter the true scene is than the blended picture ----
        var gain: Bitmap? = null
        if (wantGain && rel != null && rel.size >= n) {
            val g = FloatArray(sw * sh)
            val lnMax = ln(MAX_RATIO)
            for (y in 0 until sh) for (x in 0 until sw) {
                val i0 = y * sw + x
                var base = 0f
                var rad = 0f
                var hs = 0f
                for (k in 0 until n) {
                    val xx = (x + shifts[k][0]).coerceIn(0, sw - 1)
                    val yy = (y + shifts[k][1]).coerceIn(0, sh - 1)
                    val lv = lum[k][yy * sw + xx]
                    base += wt[k][i0] * lv
                    val v = lv / 255f
                    val h = exp(-((v - 0.5f) * (v - 0.5f)) / (2f * 0.25f * 0.25f)) + 1e-3f
                    rad += h * LIN[lv] / rel[k]
                    hs += h
                }
                val baseLin = LIN[base.toInt().coerceIn(0, 255)]
                val ratio = ((rad / hs) / max(baseLin, 2e-3f)).coerceIn(1f, MAX_RATIO)
                g[i0] = ln(ratio) / lnMax
            }
            val sm = box(g, sw, sh, 2)
            val gb = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            val gp = IntArray(sw * sh) { i ->
                val v = (sm[i] * 255f + 0.5f).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            gb.setPixels(gp, 0, sw, 0, 0, sw, sh)
            gain = gb
        }

        // ---- full-size blend, one row at a time ----
        val full = ArrayList<Bitmap>()
        for (j in jpegs.take(n)) {
            val b = BitmapFactory.decodeByteArray(j, 0, j.size) ?: return null
            if (b.width != fw || b.height != fh) {
                b.recycle()
                return null
            }
            full.add(b)
        }
        val out = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
        val sx = fw.toFloat() / sw
        val sy = fh.toFloat() / sh
        val ox = IntArray(n) { (shifts[it][0] * sx).toInt() }
        val oy = IntArray(n) { (shifts[it][1] * sy).toInt() }
        val xi = IntArray(fw)
        val xf = FloatArray(fw)
        for (x in 0 until fw) {
            val fx = ((x + 0.5f) / sx - 0.5f).coerceIn(0f, sw - 1.001f)
            xi[x] = fx.toInt()
            xf[x] = fx - xi[x]
        }
        val rows = Array(n) { IntArray(fw) }
        val outRow = IntArray(fw)
        for (y in 0 until fh) {
            val fy = ((y + 0.5f) / sy - 0.5f).coerceIn(0f, sh - 1.001f)
            val yi = fy.toInt()
            val yf = fy - yi
            for (k in 0 until n) {
                val yy = (y + oy[k]).coerceIn(0, fh - 1)
                full[k].getPixels(rows[k], 0, fw, 0, yy, fw, 1)
            }
            for (x in 0 until fw) {
                val a = yi * sw + xi[x]
                val f = xf[x]
                var r = 0f
                var g = 0f
                var b = 0f
                for (k in 0 until n) {
                    val w = wt[k]
                    val top = w[a] * (1f - f) + w[a + 1] * f
                    val bot = w[a + sw] * (1f - f) + w[a + sw + 1] * f
                    val wk = top * (1f - yf) + bot * yf
                    val xx = (x + ox[k]).coerceIn(0, fw - 1)
                    val p = rows[k][xx]
                    r += wk * ((p shr 16) and 0xFF)
                    g += wk * ((p shr 8) and 0xFF)
                    b += wk * (p and 0xFF)
                }
                outRow[x] = (0xFF shl 24) or (min(255, (r + 0.5f).toInt()) shl 16) or
                    (min(255, (g + 0.5f).toInt()) shl 8) or min(255, (b + 0.5f).toInt())
            }
            out.setPixels(outRow, 0, fw, 0, y, fw, 1)
        }
        full.forEach { it.recycle() }
        return Result(out, gain)
    }

    private val ENC = IntArray(4097) { i ->
        val v = i / 4096f
        val s = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
        (s * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private val WT = FloatArray(256) { l ->
        val v = l / 255f
        (exp(-((v - 0.5f) * (v - 0.5f)) / 0.08f) + 0.001f) * (if (l > 247) 0.02f else 1f)
    }

    /** Light in the normal frame's units to a 0..1 picture value; keeps detail up to about 3x brighter than the normal frame. */
    private fun tone(y: Float): Float {
        val a = 1.4f * y
        return min(1f, a * (1f + a / 16f) / (1f + a))
    }

    /** Shadow lift [g] (>= 1) fades out for bright pixels. */
    private fun liftFor(g: Float, y: Float): Float {
        val t = ((y - 0.12f) / 0.33f).coerceIn(0f, 1f)
        val s = t * t * (3f - 2f * t)
        return 1f + (g - 1f) * (1f - s)
    }

    private fun ghost(lv: Int, lr: Int, rel: Float): Boolean =
        lv in 12..243 && lr in 12..243 && abs(LIN[lv] / rel - LIN[lr]) / (LIN[lr] + 0.02f) > 0.55f

    /**
     * HDR+ style: the frames are merged into real (linear) light, then shadows are lifted and highlights compressed with a
     * soft local tone map. For Ultra HDR the gain map says how much brighter each pixel really was than the picture shows.
     */
    fun mergeNatural(jpegs: List<ByteArray>, rel: FloatArray, wantGain: Boolean): Result? {
        val n = min(3, min(jpegs.size, rel.size))
        if (n < 2 || rel.any { it <= 0f }) return null
        val ref = 1
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegs[ref], 0, jpegs[ref].size, bo)
        val fw = bo.outWidth
        val fh = bo.outHeight
        if (fw <= 0 || fh <= 0) return null
        var sample = 1
        while (max(fw, fh) / sample > 640) sample *= 2

        var sw = 0
        var sh = 0
        val px = ArrayList<IntArray>()
        for (j in jpegs.take(n)) {
            val o = BitmapFactory.Options()
            o.inSampleSize = sample
            val b = BitmapFactory.decodeByteArray(j, 0, j.size, o) ?: return null
            if (sw == 0) {
                sw = b.width
                sh = b.height
            } else if (b.width != sw || b.height != sh) {
                b.recycle()
                return null
            }
            val a = IntArray(sw * sh)
            b.getPixels(a, 0, sw, 0, 0, sw, sh)
            b.recycle()
            px.add(a)
        }
        val lum = Array(n) { k -> IntArray(sw * sh) { i -> lumOf(px[k][i]) } }
        val grads = Array(n) { grad(lum[it], sw, sh) }
        val shifts = Array(n) { k -> if (k == ref) intArrayOf(0, 0) else align(grads[ref], grads[k], sw, sh) }
        val sq = FloatArray(n) { Math.sqrt(rel[it].toDouble()).toFloat() }

        // small light map -> local lift
        val sn = sw * sh
        val yS = FloatArray(sn)
        for (y in 0 until sh) for (x in 0 until sw) {
            val j = y * sw + x
            var acc = 0f
            var ws = 0f
            for (k in 0 until n) {
                val xx = (x + shifts[k][0]).coerceIn(0, sw - 1)
                val yy = (y + shifts[k][1]).coerceIn(0, sh - 1)
                val i = yy * sw + xx
                val p = px[k][i]
                val lv = lum[k][i]
                var w = WT[lv] * sq[k]
                if (k != ref && ghost(lv, lum[ref][j], rel[k])) w *= 0.04f
                val ly = 0.2126f * LIN[(p shr 16) and 0xFF] + 0.7152f * LIN[(p shr 8) and 0xFF] + 0.0722f * LIN[p and 0xFF]
                acc += w * ly / rel[k]
                ws += w
            }
            yS[j] = acc / ws
        }
        // big, gentle shadow lift only: no darkening and no lift on bright pixels, so no glow around dark objects
        val rb = max(8, sw / 12)
        val yb = box(box(box(yS, sw, sh, rb), sw, sh, rb), sw, sh, rb)
        val gl = FloatArray(sn) { (0.18f / (yb[it] + 0.02f)).pow(0.28f).coerceIn(1f, 1.8f) }

        var gain: Bitmap? = null
        if (wantGain) {
            val lnMax = ln(MAX_RATIO)
            val gp = IntArray(sn)
            for (i in 0 until sn) {
                val y = yS[i]
                val ge = liftFor(gl[i], y)
                val yd = tone(y * ge)
                val yh = y * ge
                val ratio = ((yh + 0.015625f) / (yd + 0.015625f)).coerceIn(1f, MAX_RATIO)
                val v = (ln(ratio) / lnMax * 255f + 0.5f).toInt().coerceIn(0, 255)
                gp[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            val gb = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            gb.setPixels(gp, 0, sw, 0, 0, sw, sh)
            gain = gb
        }

        // full size
        val full = ArrayList<Bitmap>()
        for (j in jpegs.take(n)) {
            val b = BitmapFactory.decodeByteArray(j, 0, j.size) ?: return null
            if (b.width != fw || b.height != fh) {
                b.recycle()
                return null
            }
            full.add(b)
        }
        val out = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
        val sx = fw.toFloat() / sw
        val sy = fh.toFloat() / sh
        val ox = IntArray(n) { (shifts[it][0] * sx).toInt() }
        val oy = IntArray(n) { (shifts[it][1] * sy).toInt() }
        val xi = IntArray(fw)
        val xf = FloatArray(fw)
        for (x in 0 until fw) {
            val fx = ((x + 0.5f) / sx - 0.5f).coerceIn(0f, sw - 1.001f)
            xi[x] = fx.toInt()
            xf[x] = fx - xi[x]
        }
        val rows = Array(n) { IntArray(fw) }
        val outRow = IntArray(fw)
        for (y in 0 until fh) {
            val fy = ((y + 0.5f) / sy - 0.5f).coerceIn(0f, sh - 1.001f)
            val yi = fy.toInt()
            val yf = fy - yi
            for (k in 0 until n) {
                val yy = (y + oy[k]).coerceIn(0, fh - 1)
                full[k].getPixels(rows[k], 0, fw, 0, yy, fw, 1)
            }
            for (x in 0 until fw) {
                val lr = lumOf(rows[ref][x])
                var r = 0f
                var g = 0f
                var b = 0f
                var ws = 0f
                for (k in 0 until n) {
                    val xx = (x + ox[k]).coerceIn(0, fw - 1)
                    val p = rows[k][xx]
                    val lv = lumOf(p)
                    var w = WT[lv] * sq[k]
                    if (k != ref && ghost(lv, lr, rel[k])) w *= 0.04f
                    val ir = w / rel[k]
                    r += LIN[(p shr 16) and 0xFF] * ir
                    g += LIN[(p shr 8) and 0xFF] * ir
                    b += LIN[p and 0xFF] * ir
                    ws += w
                }
                r /= ws
                g /= ws
                b /= ws
                val yy0 = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val a = yi * sw + xi[x]
                val f = xf[x]
                val g0 = gl[a] * (1f - f) + gl[a + 1] * f
                val g1 = gl[a + sw] * (1f - f) + gl[a + sw + 1] * f
                val gLoc = liftFor(g0 * (1f - yf) + g1 * yf, yy0)
                val yd = tone(yy0 * gLoc)
                val sc = yd / max(yy0, 1e-4f)
                var r2 = r * sc
                var g2 = g * sc
                var b2 = b * sc
                r2 = yd + (r2 - yd) * 1.1f
                g2 = yd + (g2 - yd) * 1.1f
                b2 = yd + (b2 - yd) * 1.1f
                outRow[x] = (0xFF shl 24) or
                    (ENC[(r2.coerceIn(0f, 1f) * 4096f).toInt()] shl 16) or
                    (ENC[(g2.coerceIn(0f, 1f) * 4096f).toInt()] shl 8) or
                    ENC[(b2.coerceIn(0f, 1f) * 4096f).toInt()]
            }
            out.setPixels(outRow, 0, fw, 0, y, fw, 1)
        }
        full.forEach { it.recycle() }
        return Result(out, gain)
    }

    private fun grad(y: IntArray, w: Int, h: Int): IntArray {
        val g = IntArray(w * h)
        for (j in 1 until h - 1) for (i in 1 until w - 1) {
            val k = j * w + i
            g[k] = abs(y[k + 1] - y[k - 1]) + abs(y[k + w] - y[k - w])
        }
        return g
    }

    /** Shift (in small-image pixels) to read [other] at so it lines up with [ref]. */
    private fun align(ref: IntArray, other: IntArray, w: Int, h: Int): IntArray {
        var sr = 1L
        var so = 1L
        for (i in ref.indices) {
            sr += ref[i]
            so += other[i]
        }
        val norm = sr.toFloat() / so.toFloat()
        val r = min(8, min(w, h) / 8)
        var bx = 0
        var by = 0
        var best = Long.MAX_VALUE
        for (dy in -r..r) for (dx in -r..r) {
            var sad = 0L
            var y = r
            while (y < h - r) {
                var x = r
                val row = y * w
                val rowO = (y + dy) * w + dx
                while (x < w - r) {
                    sad += abs(ref[row + x] - (other[rowO + x] * norm).toInt())
                    x += 2
                }
                y += 2
            }
            if (sad < best) {
                best = sad
                bx = dx
                by = dy
            }
        }
        return intArrayOf(bx, by)
    }

    private fun box(a: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val t = FloatArray(w * h)
        val o = FloatArray(w * h)
        val n = (2 * r + 1).toFloat()
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (k in -r..r) s += a[y * w + (x + k).coerceIn(0, w - 1)]
            t[y * w + x] = s / n
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (k in -r..r) s += t[(y + k).coerceIn(0, h - 1) * w + x]
            o[y * w + x] = s / n
        }
        return o
    }
}
