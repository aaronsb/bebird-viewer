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
        assertEquals("bebird-20260102-030405_zoomed.jpg", CaptureNames.zoomed(CaptureNames.still(t)))
        assertEquals("bebird-20260102-030405.mp4", CaptureNames.video(t))
    }

    @Test fun theAnnotatedCopySharesTheStillsName() {
        val t = LocalDateTime.of(2026, 9, 29, 23, 59, 58)
        assertEquals("bebird-20260929-235958_annotated.jpg", CaptureNames.annotated(CaptureNames.still(t)))
    }

    @Test fun theZoomedCropFollowsTheNameTheStillWasSavedUnder() {
        // two snapshots in one second: the second's full frame was renamed, its crop goes with it
        assertEquals("bebird-20260929-235958 (1)_zoomed.jpg", CaptureNames.zoomed("bebird-20260929-235958 (1).jpg"))
    }

    @Test fun theAnnotatedCopyFollowsTheNameTheStillWasSavedUnder() {
        // a snapshot took bebird-…-235958.jpg that second, so MediaStore renamed this still
        assertEquals("bebird-20260929-235958 (1)_annotated.jpg", CaptureNames.annotated("bebird-20260929-235958 (1).jpg"))
        assertEquals("bebird-20260929-235958 (2)_annotated.jpg", CaptureNames.annotated("bebird-20260929-235958 (2).jpg"))
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

    @Test fun annotatedCopiesSaySoAndOthersAreUnchanged() {
        val plain = meta.description()
        assertFalse("annotated" in plain || "annotated" in meta.json())
        val annotated = meta.copy(annotated = true)
        assertEquals("$plain, annotated", annotated.description())
        assertEquals(meta.json().removeSuffix("}") + ", \"annotated\": true}", annotated.json())
        // and in the EXIF: ImageDescription and UserComment, the rest as the original's
        val (ifd0, exif) = parse(ExifWriter.segment(annotated))
        assertEquals(annotated.description(), ascii(ifd0.getValue(ExifWriter.IMAGE_DESCRIPTION)))
        assertEquals("ASCII\u0000\u0000\u0000" + annotated.json(), String(exif.getValue(ExifWriter.USER_COMMENT).third, Charsets.US_ASCII))
        val (plainIfd0, plainExif) = parse(ExifWriter.segment(meta))
        assertEquals(plainIfd0.keys, ifd0.keys); assertEquals(plainExif.keys, exif.keys)
        assertEquals(ascii(plainIfd0.getValue(ExifWriter.DATE_TIME)), ascii(ifd0.getValue(ExifWriter.DATE_TIME)))
    }

    @Test fun modelDefaultsToEs() {
        val (ifd0, _) = parse(ExifWriter.segment(meta.copy(model = null)))
        assertEquals("ES", ascii(ifd0.getValue(ExifWriter.MODEL)))
    }

    private fun seg(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val jfif = seg(0xE0, "JFIF\u0000".toByteArray() + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0))
    private val oldExif = seg(0xE1, "Exif\u0000\u0000".toByteArray() + ByteArray(20))
    private val xmp = seg(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x/>".toByteArray())
    private val dqt = seg(0xDB, ByteArray(5))
    private val scan = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 0x11, 0xFF.toByte(), 0xE1.toByte(), 0x22)  // SOS + data that looks like a marker
    private val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** The Exif APP1 segments in [jpeg]'s header, in order. */
    private fun exifSegments(jpeg: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var at = 2
        while (at + 4 <= jpeg.size && jpeg[at] == 0xFF.toByte() && jpeg[at + 1] != 0xDA.toByte()) {
            val end = at + 2 + (((jpeg[at + 2].toInt() and 0xFF) shl 8) or (jpeg[at + 3].toInt() and 0xFF))
            if (jpeg[at + 1] == 0xE1.toByte() && String(jpeg, at + 4, 4) == "Exif") out += jpeg.copyOfRange(at, end)
            at = end
        }
        return out
    }

    @Test fun jfifStaysFirstThenOurExif() {
        val out = ExifWriter.insert(soi + jfif + dqt + scan + eoi, meta)
        assertArrayEquals(soi + jfif + ExifWriter.segment(meta) + dqt + scan + eoi, out)
    }

    @Test fun withoutJfifExifComesRightAfterSoi() {
        val out = ExifWriter.insert(soi + dqt + scan + eoi, meta)
        assertArrayEquals(soi + ExifWriter.segment(meta) + dqt + scan + eoi, out)
    }

    @Test fun anExistingExifIsReplacedAndOtherApp1Kept() {
        val out = ExifWriter.insert(soi + jfif + oldExif + xmp + dqt + scan + eoi, meta)
        assertArrayEquals(soi + jfif + ExifWriter.segment(meta) + xmp + dqt + scan + eoi, out)
    }

    @Test fun insertingAgainLeavesExactlyOneExifThatRoundTrips() {
        val once = ExifWriter.insert(soi + jfif + dqt + scan + eoi, meta.copy(label = "first"))
        val twice = ExifWriter.insert(once, meta)
        val exif = exifSegments(twice)
        assertEquals(1, exif.size)
        val (ifd0, sub) = parse(exif.single())
        assertEquals(meta.description(), ascii(ifd0.getValue(ExifWriter.IMAGE_DESCRIPTION)))
        assertEquals("ASCII\u0000\u0000\u0000" + meta.json(), String(sub.getValue(ExifWriter.USER_COMMENT).third, Charsets.US_ASCII))
        // the image data after SOS is untouched, even bytes that look like markers
        assertArrayEquals(scan + eoi, twice.copyOfRange(twice.size - scan.size - eoi.size, twice.size))
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

    // --- pixel steps ---

    private fun image(w: Int, h: Int) = com.bockelie.bebird.band.PixelImage(w, h, IntArray(w * h) { (0xFF shl 24) or it })

    @Test fun anEnlargedCropRepeatsEachSourcePixel() {
        val src = image(480, 480)
        val rect = ZoomCrop.Rect(100, 50, 220, 170)  // 120 px: 4x makes 480
        val (crop, _) = PixelOps.enlargedCrop(src, rect)
        assertEquals(480, crop.width); assertEquals(480, crop.height)
        for (y in 0 until 480 step 7) for (x in 0 until 480 step 5) {
            assertEquals(src.pixels[(50 + y / 4) * 480 + 100 + x / 4], crop.pixels[y * 480 + x])
        }
        // no new pixel values: nearest neighbour only
        assertTrue(crop.pixels.toSet().all { it in src.pixels.toSet() })
    }

    @Test fun theCropsCircleIsTheFramesCircleMoved() {
        val (crop, c) = PixelOps.enlargedCrop(image(480, 480), ZoomCrop.Rect(120, 120, 360, 360))  // 2x
        assertEquals(480, crop.width)
        // the frame's centre (239.5) is the crop's centre, and the radius doubles
        assertEquals(239.5, c.cx, 1e-9); assertEquals(239.5, c.cy, 1e-9)
        assertEquals(479.5, c.r, 1e-9)
        // off-centre: a crop from the left edge puts the centre to the right
        val (_, left) = PixelOps.enlargedCrop(image(480, 480), ZoomCrop.Rect(0, 120, 240, 360))
        assertEquals(479.5, left.cx, 1e-9)  // (239.5 - 0) * 2 + (2 - 1) / 2
        assertEquals(239.5, left.cy, 1e-9)  // (239.5 - 120) * 2 + 0.5
    }

    @Test fun fitPadsOrCutsToTheFileSize() {
        val small = image(4, 2)
        val padded = PixelOps.fit(small, 6, 3)
        assertEquals(small.pixels[5], padded.pixels[1 * 6 + 1])
        assertEquals(com.bockelie.bebird.band.BandRenderer.BACKGROUND, padded.pixels[2 * 6 + 5])
        val cut = PixelOps.fit(image(6, 3), 4, 2)
        assertEquals(image(6, 3).pixels[1 * 6 + 3], cut.pixels[1 * 4 + 3])
        val same = image(4, 2)
        assertTrue(PixelOps.fit(same, 4, 2) === same)
    }

    @Test fun utcIsWrittenAsPlusZero() {
        val utc = meta.copy(taken = ZonedDateTime.of(2026, 9, 28, 14, 3, 7, 0, ZoneOffset.UTC))
        assertTrue(utc.json().startsWith("{\"taken\": \"2026-09-28T14:03:07+00:00\""))
    }

    @Test fun jsonHasNoNaN() {
        assertEquals("{\"a\": null, \"b\": null, \"c\": 2.5}", Json.write(linkedMapOf("a" to Double.NaN, "b" to Double.POSITIVE_INFINITY, "c" to 2.5)))
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
        // reusing planes gives the same result
        val planes = Yuv.planes(2, 2)
        Yuv.into(IntArray(4) { red }, planes)
        assertEquals(82, planes.y[3].toInt() and 0xFF)
        Yuv.into(IntArray(4) { white }, planes)
        assertEquals(235, planes.y[3].toInt() and 0xFF); assertEquals(128, planes.v[0].toInt() and 0xFF)
    }
}
