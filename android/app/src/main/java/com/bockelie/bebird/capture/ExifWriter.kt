// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.format.DateTimeFormatter

/**
 * Writes the Exif APP1 segment for a snapshot and puts it into a JPEG. Written here rather than
 * with androidx.exifinterface because that can't write XPComment/XPSubject, which carry the
 * label in UTF-16 (ImageDescription is ASCII-only). Pure Kotlin; the tests read it back.
 */
object ExifWriter {
    // IFD0
    const val ORIENTATION = 0x0112
    const val IMAGE_DESCRIPTION = 0x010E
    const val MAKE = 0x010F
    const val MODEL = 0x0110
    const val SOFTWARE = 0x0131
    const val DATE_TIME = 0x0132
    const val EXIF_IFD = 0x8769
    const val XP_COMMENT = 0x9C9C
    const val XP_SUBJECT = 0x9C9F
    // Exif sub-IFD
    const val EXIF_VERSION = 0x9000
    const val DATE_TIME_ORIGINAL = 0x9003
    const val OFFSET_TIME_ORIGINAL = 0x9011
    const val USER_COMMENT = 0x9286

    const val BYTE = 1
    const val ASCII = 2
    const val SHORT = 3
    const val LONG = 4
    const val UNDEFINED = 7

    /** One IFD entry: [count] values of [type] in [data] (already encoded). */
    class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    /** The tags for [meta], by IFD: IFD0 first, then the Exif sub-IFD. */
    fun tags(meta: SnapshotMeta): Pair<List<Entry>, List<Entry>> {
        val stamp = meta.taken.format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
        val offset = meta.taken.offset.id.let { if (it == "Z") "+00:00" else it }
        val ifd0 = mutableListOf(
            short(ORIENTATION, 1),  // rotation is already in the pixels
            ascii(IMAGE_DESCRIPTION, meta.description()),
            ascii(MAKE, "Bebird"),
            ascii(MODEL, meta.model ?: "ES"),
            ascii(SOFTWARE, "bebird-viewer"),
            ascii(DATE_TIME, stamp),
        )
        meta.label?.let {
            ifd0 += utf16(XP_COMMENT, it)
            ifd0 += utf16(XP_SUBJECT, it)
        }
        val exif = listOf(
            Entry(EXIF_VERSION, UNDEFINED, 4, "0232".toByteArray(Charsets.US_ASCII)),
            ascii(DATE_TIME_ORIGINAL, stamp),
            ascii(OFFSET_TIME_ORIGINAL, offset),
            Entry(USER_COMMENT, UNDEFINED, 8 + meta.json().length,
                "ASCII\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII) + meta.json().toByteArray(Charsets.US_ASCII)),
        )
        return ifd0 to exif
    }

    /** The APP1 segment (marker FFE1, length, "Exif\0\0", little-endian TIFF). */
    fun segment(meta: SnapshotMeta): ByteArray {
        val (ifd0, exif) = tags(meta)
        val tiff = tiff(ifd0, exif)
        val header = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII)
        val length = 2 + header.size + tiff.size
        require(length <= 0xFFFF) { "Exif segment too long: $length" }
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + header + tiff
    }

    /**
     * [jpeg] with the Exif segment right after SOI, replacing a JFIF APP0 segment if the encoder
     * wrote one (an Exif file doesn't need it).
     */
    fun insert(jpeg: ByteArray, meta: SnapshotMeta): ByteArray {
        require(jpeg.size >= 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "not a JPEG" }
        var rest = 2
        if (jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte()) {
            val len = ((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF)
            rest = 4 + len
        }
        return jpeg.copyOf(2) + segment(meta) + jpeg.copyOfRange(rest, jpeg.size)
    }

    private fun tiff(ifd0: List<Entry>, exif: List<Entry>): ByteArray {
        // Layout: header (8) | IFD0 | IFD0 values | Exif IFD | Exif values
        val ifd0WithPointer = (ifd0 + Entry(EXIF_IFD, LONG, 1, ByteArray(4))).sortedBy { it.tag }
        val ifd0Size = ifdSize(ifd0WithPointer)
        val exifStart = 8 + ifd0Size
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0, 8, 0, 0, 0))
        out.write(ifd(ifd0WithPointer.map { if (it.tag == EXIF_IFD) Entry(EXIF_IFD, LONG, 1, le32(exifStart)) else it }, 8))
        out.write(ifd(exif.sortedBy { it.tag }, exifStart))
        return out.toByteArray()
    }

    /** Bytes an IFD takes with its out-of-line values: count, 12 per entry, next-IFD offset, values. */
    private fun ifdSize(entries: List<Entry>) = 2 + 12 * entries.size + 4 + entries.sumOf { outOfLine(it) }

    private fun outOfLine(e: Entry) = if (e.data.size > 4) e.data.size + (e.data.size and 1) else 0

    /** An IFD placed at [start] (offsets are from the TIFF header), with no next IFD. */
    private fun ifd(entries: List<Entry>, start: Int): ByteArray {
        val size = ifdSize(entries)
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(entries.size.toShort())
        var valueAt = start + 2 + 12 * entries.size + 4
        val values = ByteArrayOutputStream()
        for (e in entries) {
            buf.putShort(e.tag.toShort()).putShort(e.type.toShort()).putInt(e.count)
            if (e.data.size <= 4) {
                buf.put(e.data.copyOf(4))
            } else {
                buf.putInt(valueAt)
                values.write(e.data)
                if (e.data.size and 1 == 1) values.write(0)  // values start on word boundaries
                valueAt += outOfLine(e)
            }
        }
        buf.putInt(0)
        buf.put(values.toByteArray())
        return buf.array()
    }

    private fun short(tag: Int, v: Int) = Entry(tag, SHORT, 1, byteArrayOf(v.toByte(), (v shr 8).toByte()))

    /** ASCII, NUL-terminated; anything outside printable ASCII becomes '?'. */
    private fun ascii(tag: Int, s: String): Entry {
        val bytes = s.map { if (it.code in 0x20..0x7E) it.code.toByte() else '?'.code.toByte() }.toByteArray() + 0
        return Entry(tag, ASCII, bytes.size, bytes)
    }

    /** Windows XP tags: UTF-16LE, NUL-terminated, as BYTE. */
    private fun utf16(tag: Int, s: String): Entry {
        val bytes = s.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
        return Entry(tag, BYTE, bytes.size, bytes)
    }

    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
}
