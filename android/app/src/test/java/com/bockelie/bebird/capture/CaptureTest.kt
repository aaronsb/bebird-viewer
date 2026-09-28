// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

class CaptureTest {
    private val taken = ZonedDateTime.of(2026, 9, 28, 14, 3, 7, 123_000_000, ZoneOffset.ofHours(-7))
    private val meta = SnapshotMeta(
        taken = taken, roll = 47, rotationApplied = 62, autoRotate = true, trim = 15,
        lightPercent = 100, lightRaw = 50, batteryPercent = 86, batteryState = "battery", fps = 10,
        zoom = 1.0, zoomed = false, label = "left ear", device = "ES-123456", model = "ES",
    )

    // --- file names ---

    @Test fun names() {
        val t = LocalDateTime.of(2026, 1, 2, 3, 4, 5)
        assertEquals("bebird-20260102-030405.jpg", CaptureNames.still(t))
        assertEquals("bebird-20260102-030405_zoomed.jpg", CaptureNames.still(t, zoomed = true))
        assertEquals("bebird-20260102-030405.mp4", CaptureNames.video(t))
    }

    // --- metadata ---

    @Test fun descriptionMatchesTheDesktop() {
        assertEquals(
            "roll 47 deg, rotated 62 deg (auto) + trim 15 deg, light 100% (scope 50), battery 86% (battery), label left ear",
            meta.description(),
        )
        assertEquals(
            "roll 47 deg, rotated 15 deg (manual) + trim 15 deg, light 0% (scope 0), zoom 2.50x crop",
            meta.copy(rotationApplied = 15, autoRotate = false, lightPercent = 0, lightRaw = 0, batteryPercent = null,
                zoom = 2.5, zoomed = true, label = "Жанна").description(),  // a non-ASCII label stays out of ASCII fields
        )
    }

    @Test fun jsonIsAsciiAndComplete() {
        val json = meta.copy(label = "Жанна \"J\" 山").json()
        assertTrue(json.all { it.code in 0x20..0x7E })
        assertTrue(json.startsWith("{\"taken\": \"2026-09-28T14:03:07-07:00\""))
        for (key in listOf("roll_deg", "rotation_applied_deg", "auto_rotate", "trim_deg", "light_pct", "light_scope_level",
            "battery_pct", "battery_state", "fps", "zoom", "zoomed_crop", "label", "device", "scope")) {
            assertTrue("missing $key in $json", "\"$key\":" in json)
        }
        assertTrue("\"label\": \"\\u0416\\u0430\\u043d\\u043d\\u0430 \\\"J\\\" \\u5c71\"" in json)
        assertTrue("\"scope\": {\"brand\": \"bebird\", \"model\": \"ES\"}" in json)
        assertTrue("\"zoom\": 1," in json)
    }

    @Test fun nothingIdentifiesTheScope() {
        val json = meta.json().lowercase()
        for (word in listOf("serial", "uuid", "mac", "bssid", "ssid\"", "password")) assertFalse("$word in $json", word in json)
    }

    // --- EXIF ---

    /** Reads back what ExifWriter wrote: tag -> (type, count, value bytes), for IFD0 and the Exif IFD. */
    private fun parse(segment: ByteArray): Pair<Map<Int, Triple<Int, Int, ByteArray>>, Map<Int, Triple<Int, Int, ByteArray>>> {
        assertEquals(0xFF.toByte(), segment[0]); assertEquals(0xE1.toByte(), segment[1])
        assertEquals(segment.size - 2, ((segment[2].toInt() and 0xFF) shl 8) or (segment[3].toInt() and 0xFF))
        assertArrayEquals("Exif\u0000\u0000".toByteArray(), segment.copyOfRange(4, 10))
        val tiff = ByteBuffer.wrap(segment, 10, segment.size - 10).slice().order(ByteOrder.LITTLE_ENDIAN)
        assertEquals('I'.code.toByte(), tiff.get(0)); assertEquals(42, tiff.getShort(2).toInt())
        fun ifd(at: Int): Map<Int, Triple<Int, Int, ByteArray>> {
            val n = tiff.getShort(at).toInt()
            return (0 until n).associate { i ->
                val e = at + 2 + 12 * i
                val tag = tiff.getShort(e).toInt() and 0xFFFF
                val type = tiff.getShort(e + 2).toInt()
                val count = tiff.getInt(e + 4)
                val size = count * when (type) { 3 -> 2; 4 -> 4; else -> 1 }
                val from = if (size <= 4) e + 8 else tiff.getInt(e + 8)
                tag to Triple(type, count, ByteArray(size) { tiff.get(from + it) })
            }
        }
        val ifd0 = ifd(tiff.getInt(4))
        val exifAt = ByteBuffer.wrap(ifd0.getValue(ExifWriter.EXIF_IFD).third).order(ByteOrder.LITTLE_ENDIAN).int
        return ifd0 to ifd(exifAt)
    }

    private fun ascii(v: Triple<Int, Int, ByteArray>): String {
        assertEquals(ExifWriter.ASCII, v.first)
        assertEquals(0.toByte(), v.third.last())
        return String(v.third, 0, v.third.size - 1, Charsets.US_ASCII)
    }

    @Test fun exifTagsMatchTheDesktop() {
        val (ifd0, exif) = parse(ExifWriter.segment(meta))
        assertEquals(1, ifd0.getValue(ExifWriter.ORIENTATION).third[0].toInt())
        assertEquals(meta.description(), ascii(ifd0.getValue(ExifWriter.IMAGE_DESCRIPTION)))
        assertEquals("Bebird", ascii(ifd0.getValue(ExifWriter.MAKE)))
        assertEquals("ES", ascii(ifd0.getValue(ExifWriter.MODEL)))
        assertEquals("bebird-viewer", ascii(ifd0.getValue(ExifWriter.SOFTWARE)))
        assertEquals("2026:09:28 14:03:07", ascii(ifd0.getValue(ExifWriter.DATE_TIME)))
        assertEquals("2026:09:28 14:03:07", ascii(exif.getValue(ExifWriter.DATE_TIME_ORIGINAL)))
        assertEquals("-07:00", ascii(exif.getValue(ExifWriter.OFFSET_TIME_ORIGINAL)))
        val comment = exif.getValue(ExifWriter.USER_COMMENT)
        assertEquals(ExifWriter.UNDEFINED, comment.first)
        assertEquals("ASCII\u0000\u0000\u0000" + meta.json(), String(comment.third, Charsets.US_ASCII))
        // IFD entries are in ascending tag order, as TIFF requires
        assertEquals(ifd0.keys.sorted(), ifd0.keys.toList())
    }

    @Test fun theLabelIsInXpTagsAsUtf16() {
        val label = "Жанна 山田 😀"
        val (ifd0, _) = parse(ExifWriter.segment(meta.copy(label = label)))
        for (tag in listOf(ExifWriter.XP_COMMENT, ExifWriter.XP_SUBJECT)) {
            val v = ifd0.getValue(tag)
            assertEquals(ExifWriter.BYTE, v.first)
            assertEquals(label, String(v.third, 0, v.third.size - 2, Charsets.UTF_16LE))
            assertEquals(0.toByte(), v.third[v.third.size - 1]); assertEquals(0.toByte(), v.third[v.third.size - 2])
        }
        val (noLabel, _) = parse(ExifWriter.segment(meta.copy(label = null)))
        assertFalse(ExifWriter.XP_COMMENT in noLabel || ExifWriter.XP_SUBJECT in noLabel)
    }

    @Test fun modelDefaultsToEs() {
        val (ifd0, _) = parse(ExifWriter.segment(meta.copy(model = null)))
        assertEquals("ES", ascii(ifd0.getValue(ExifWriter.MODEL)))
    }

    @Test fun exifGoesRightAfterSoiReplacingJfif() {
        val app0 = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 4, 1, 2)
        val body = byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0, 2, 0xFF.toByte(), 0xD9.toByte())
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + body
        val out = ExifWriter.insert(jpeg, meta)
        val seg = ExifWriter.segment(meta)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + seg + body, out)
        // without an APP0 the rest is kept as is
        val plain = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + body
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + seg + body, ExifWriter.insert(plain, meta))
    }

    // --- zoom crop ---

    @Test fun notZoomedIsNoCrop() {
        assertNull(ZoomCrop.visible(480, 1000f, 1200f, 1000f, 1f, 0f, 0f))
    }

    @Test fun centredZoomCropsTheMiddle() {
        // a 1000 px viewport square, frame shown 1000 px, zoom 2: the middle half of the frame
        assertEquals(ZoomCrop.Rect(120, 120, 360, 360), ZoomCrop.visible(480, 1000f, 1000f, 1000f, 2f, 0f, 0f))
        // zoom 4: the middle quarter
        assertEquals(ZoomCrop.Rect(180, 180, 300, 300), ZoomCrop.visible(480, 1000f, 1000f, 1000f, 4f, 0f, 0f))
    }

    @Test fun panMovesTheCropTheOtherWay() {
        // content moved right by 500 screen px at zoom 2 (= 120 frame px): we see further left
        assertEquals(ZoomCrop.Rect(0, 120, 240, 360), ZoomCrop.visible(480, 1000f, 1000f, 1000f, 2f, 500f, 0f))
    }

    @Test fun aWideViewportShowsAllRowsButNotAllColumns() {
        // viewport 1000 x 600, circle 600 px; zoom 2 makes it 1200 px: all rows? no: 600 of 1200 visible vertically
        val r = ZoomCrop.visible(480, 1000f, 600f, 600f, 2f, 0f, 0f)!!
        assertEquals(ZoomCrop.Rect(40, 120, 440, 360), r)
    }

    @Test fun upscaleReachesTheBandWidth() {
        assertEquals(2, ZoomCrop.upscale(240))
        assertEquals(4, ZoomCrop.upscale(120))
        assertEquals(3, ZoomCrop.upscale(170))
        assertEquals(1, ZoomCrop.upscale(480))
    }

    // --- video timing and pixels ---

    @Test fun ptsFromTheWallClock() {
        val c = PtsClock()
        assertEquals(0, c.ptsUs(5_000_000_000))
        assertEquals(100_000, c.ptsUs(5_100_000_000))   // 100 ms later
        assertEquals(212_000, c.ptsUs(5_212_000_000))   // irregular gaps are kept
        assertEquals(212_001, c.ptsUs(5_212_000_000))   // a repeat still moves forward
        assertEquals(212_002, c.ptsUs(5_000_000_000))   // and so does a clock going back
    }

    @Test fun yuvOfKnownColours() {
        val w = 4; val h = 2
        val white = 0xFFFFFFFF.toInt(); val black = 0xFF000000.toInt(); val red = 0xFFFF0000.toInt()
        val px = intArrayOf(white, white, black, black, white, white, black, black)
        val yuv = Yuv.from(px, w, h)
        assertEquals(235, yuv.y[0].toInt() and 0xFF)
        assertEquals(16, yuv.y[2].toInt() and 0xFF)
        assertEquals(128, yuv.u[0].toInt() and 0xFF); assertEquals(128, yuv.v[0].toInt() and 0xFF)
        assertEquals(128, yuv.u[1].toInt() and 0xFF); assertEquals(128, yuv.v[1].toInt() and 0xFF)
        val r = Yuv.from(IntArray(4) { red }, 2, 2)
        assertEquals(82, r.y[0].toInt() and 0xFF)
        assertEquals(90, r.u[0].toInt() and 0xFF)
        assertEquals(240, r.v[0].toInt() and 0xFF)
        assertEquals(528, Yuv.padTo16(520)); assertEquals(480, Yuv.padTo16(480))
    }
}
