package com.example.videocam

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.ln

/**
 * Writes an Ultra HDR JPEG: the normal photo plus a gain map, packed the way Google Photos and Android 14+ expect
 * (primary JPEG with XMP + multi-picture directory, followed by the gain map as a second JPEG). On an HDR screen the
 * highlights then show their real brightness; everywhere else it is just a normal JPEG.
 */
object UltraHdr {
    private const val OFFSET = "0.015625"

    private fun jpeg(b: Bitmap, q: Int): ByteArray {
        val o = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, q, o)
        return o.toByteArray()
    }

    private fun app1(xmp: String): ByteArray {
        val head = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
        val body = xmp.toByteArray(Charsets.UTF_8)
        val len = 2 + head.size + body.size
        val o = ByteArrayOutputStream()
        o.write(0xFF)
        o.write(0xE1)
        o.write(len shr 8)
        o.write(len and 0xFF)
        o.write(head)
        o.write(body)
        return o.toByteArray()
    }

    private fun mpf(primarySize: Int, secondarySize: Int, secondaryOffset: Int): ByteArray {
        val b = ByteArrayOutputStream()
        fun w16(v: Int) {
            b.write(v and 0xFF)
            b.write((v shr 8) and 0xFF)
        }
        fun w32(v: Int) {
            b.write(v and 0xFF)
            b.write((v shr 8) and 0xFF)
            b.write((v shr 16) and 0xFF)
            b.write((v shr 24) and 0xFF)
        }
        b.write(byteArrayOf(0x4D, 0x50, 0x46, 0x00)) // "MPF\0"
        b.write(byteArrayOf(0x49, 0x49, 0x2A, 0x00)) // little endian TIFF header
        w32(8)
        w16(3)
        w16(0xB000); w16(7); w32(4); b.write("0100".toByteArray(Charsets.US_ASCII))
        w16(0xB001); w16(4); w32(1); w32(2)
        w16(0xB002); w16(7); w32(32); w32(50)
        w32(0)
        w32(0x20030000); w32(primarySize); w32(0); w16(0); w16(0)
        w32(0); w32(secondarySize); w32(secondaryOffset); w16(0); w16(0)
        return b.toByteArray()
    }

    /** Returns the finished file, or null if something went wrong (the caller then saves a normal JPEG). */
    fun encode(base: Bitmap, gain: Bitmap, quality: Int, maxRatio: Float): ByteArray? {
        return try {
            val baseJpeg = jpeg(base, quality)
            val gainJpeg = jpeg(gain, 92)
            if (baseJpeg.size < 4 || gainJpeg.size < 4) return null
            val log2Max = (ln(maxRatio.toDouble()) / ln(2.0))
            val maxS = String.format(Locale.US, "%.5f", log2Max)

            val xmpGain = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.2"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0" hdrgm:GainMapMin="0" hdrgm:GainMapMax="$maxS" hdrgm:Gamma="1" hdrgm:OffsetSDR="$OFFSET" hdrgm:OffsetHDR="$OFFSET" hdrgm:HDRCapacityMin="0" hdrgm:HDRCapacityMax="$maxS" hdrgm:BaseRenditionIsHDR="False"/></rdf:RDF></x:xmpmeta>"""
            val sec = ByteArrayOutputStream()
            sec.write(0xFF); sec.write(0xD8)
            sec.write(app1(xmpGain))
            sec.write(gainJpeg, 2, gainJpeg.size - 2)
            val secondary = sec.toByteArray()

            val xmpMain = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.2"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/" xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"><Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/></rdf:li><rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="${secondary.size}"/></rdf:li></rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
            val main = app1(xmpMain)
            val mpfLen = 4 + 82 // "MPF\0" + TIFF part
            val app2Size = 2 + 2 + mpfLen
            val baseRest = baseJpeg.size - 2
            val primarySize = 2 + main.size + app2Size + baseRest
            val tiffStart = 2 + main.size + 2 + 2 + 4
            val mp = mpf(primarySize, secondary.size, primarySize - tiffStart)

            val o = ByteArrayOutputStream(primarySize + secondary.size)
            o.write(0xFF); o.write(0xD8)
            o.write(main)
            o.write(0xFF); o.write(0xE2)
            o.write((2 + mp.size) shr 8); o.write((2 + mp.size) and 0xFF)
            o.write(mp)
            o.write(baseJpeg, 2, baseRest)
            o.write(secondary)
            o.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }
}
