// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FrameAssemblerTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // A tiny stand-in JPEG: SOI, some bytes, EOI
    private val jpeg = b(0xFF, 0xD8, 1, 2, 3, 4, 5, 6, 7, 8, 0xFF, 0xD9)

    private fun packet(id: Int, last: Int, index: Int, angle: Int, payload: ByteArray) =
        b(id, last, index, angle) + payload

    /** Split [data] into packets of [chunk] bytes; the last one carries [flag] and [angle]. */
    private fun packets(id: Int, data: ByteArray, chunk: Int, angle: Int = 0, flag: Int = 1) =
        data.toList().chunked(chunk).mapIndexed { i, part ->
            val isLast = (i + 1) * chunk >= data.size
            packet(id, if (isLast) flag else 0, i + 1, if (isLast) angle else 0, part.toByteArray())
        }

    private fun FrameAssembler.feed(ps: List<ByteArray>) = ps.mapNotNull { accept(it) }

    @Test fun singlePacketFrame() {
        val f = FrameAssembler().accept(packet(7, 1, 1, 42, jpeg))
        assertNotNull(f)
        assertArrayEquals(jpeg, f!!.jpeg)
        assertEquals(42, f.angle)
    }

    @Test fun multiPacketFrameInOrder() {
        val a = FrameAssembler()
        val frames = a.feed(packets(3, jpeg, 5, angle = 90))
        assertEquals(1, frames.size)
        assertArrayEquals(jpeg, frames[0].jpeg)
        assertEquals(90, frames[0].angle)
        assertEquals(1, a.done)
    }

    @Test fun outOfOrderPacketsAreOrderedByIndex() {
        val ps = packets(3, jpeg, 4)          // indices 1, 2, 3 (3 is last)
        val frames = FrameAssembler().feed(listOf(ps[1], ps[0], ps[2]))
        assertEquals(1, frames.size)
        assertArrayEquals(jpeg, frames[0].jpeg)
    }

    @Test fun angleAbove255FromFlag2() {
        val f = FrameAssembler().feed(packets(1, jpeg, 6, angle = 300 - 256, flag = 2)).single()
        assertEquals(300, f.angle)
    }

    @Test fun gapDropsFrame() {
        val a = FrameAssembler()
        val ps = packets(5, jpeg, 4)
        assertEquals(0, a.feed(listOf(ps[0], ps[2])).size)  // index 2 missing
        assertEquals(1, a.dropped)
        // the next frame is unaffected
        assertEquals(1, a.feed(packets(6, jpeg, 4)).size)
    }

    @Test fun frameIdChangeMidFrameDiscardsPartial() {
        val a = FrameAssembler()
        val old = packets(9, jpeg, 4)
        val new = packets(10, jpeg, 4)
        val frames = a.feed(listOf(old[0], old[1], new[0], new[1], new[2]))
        assertEquals(1, frames.size)
        assertArrayEquals(jpeg, frames[0].jpeg)
        assertEquals(1, a.superseded)
    }

    @Test fun lateLastPacketOfOldFrameAfterIdChangeIsDropped() {
        // old frame's packets 1-2 are thrown away when id 10 starts; its last packet alone has a gap
        val a = FrameAssembler()
        val old = packets(9, jpeg, 4)
        val frames = a.feed(listOf(old[0], old[1], packets(10, jpeg, 4)[0], old[2]))
        assertEquals(0, frames.size)
        assertEquals(1, a.dropped)
    }

    @Test fun lastPacketBeforeEarlierIndexDropsFrame() {
        // viewer.py checks for gaps when the flagged packet arrives; a late earlier index can't save it
        val a = FrameAssembler()
        val ps = packets(4, jpeg, 4)          // indices 1, 2, 3 (3 is last)
        assertEquals(0, a.feed(listOf(ps[0], ps[2], ps[1])).size)
        assertEquals(1, a.dropped)
        assertEquals(0, a.done)
    }

    @Test fun repeatedIndexReplacesEarlierPayload() {
        val ps = packets(4, jpeg, 6)          // indices 1, 2 (2 is last)
        val stale = packet(4, 0, 1, 0, b(9, 9, 9, 9, 9, 9))
        val frames = FrameAssembler().feed(listOf(stale, ps[0], ps[1]))
        assertEquals(1, frames.size)
        assertArrayEquals(jpeg, frames[0].jpeg)
    }

    @Test(expected = IllegalArgumentException::class)
    fun lengthBeyondPacketRejected() {
        FrameAssembler().accept(packet(1, 1, 1, 0, jpeg), 4 + jpeg.size + 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeLengthRejected() {
        FrameAssembler().accept(packet(1, 1, 1, 0, jpeg), -1)
    }

    @Test fun runtPacketsIgnored() {
        val a = FrameAssembler()
        assertNull(a.accept(b(1, 1, 1, 0)))  // header only
        assertNull(a.accept(b(1, 1)))
        assertEquals(0, a.packets)
    }

    @Test fun lengthLimitsPacket() {
        val buf = packet(1, 1, 1, 0, jpeg) + ByteArray(20) { 0x55 }
        val f = FrameAssembler().accept(buf, 4 + jpeg.size)
        assertArrayEquals(jpeg, f!!.jpeg)
    }

    @Test fun nonJpegIsNotEmitted() {
        val a = FrameAssembler()
        assertNull(a.accept(packet(1, 1, 1, 0, b(0x00, 0x11, 0xFF, 0xD9))))
        assertEquals(0, a.done)
    }

    @Test fun trailingGarbageAfterEoiIsTrimmed() {
        val f = FrameAssembler().accept(packet(1, 1, 1, 0, jpeg + b(0, 0, 0)))
        assertArrayEquals(jpeg, f!!.jpeg)
    }

    @Test fun missingEoiSecondByteRepairedWithD9Only() {
        val truncated = jpeg.copyOf(jpeg.size - 1)  // ends in FF
        val f = FrameAssembler().accept(packet(1, 1, 1, 0, truncated))
        assertArrayEquals(jpeg, f!!.jpeg)
    }

    @Test fun closeJpegCases() {
        // trims at the last FF D9
        assertArrayEquals(b(0xFF, 0xD8, 0xFF, 0xD9, 9, 0xFF, 0xD9),
            FrameAssembler.closeJpeg(b(0xFF, 0xD8, 0xFF, 0xD9, 9, 0xFF, 0xD9, 1, 2)))
        // no marker, trailing FF: append D9 only
        assertArrayEquals(b(0xFF, 0xD8, 1, 0xFF, 0xD9), FrameAssembler.closeJpeg(b(0xFF, 0xD8, 1, 0xFF)))
        // no marker at all: append FF D9
        assertArrayEquals(b(0xFF, 0xD8, 1, 0xFF, 0xD9), FrameAssembler.closeJpeg(b(0xFF, 0xD8, 1)))
        // marker only at offset 0 doesn't count (grab.py tests end > 0)
        assertArrayEquals(b(0xFF, 0xD9, 5, 0xFF, 0xD9), FrameAssembler.closeJpeg(b(0xFF, 0xD9, 5)))
        assertArrayEquals(b(0xFF, 0xD9), FrameAssembler.closeJpeg(ByteArray(0)))
    }

    @Test fun consecutiveFramesSameAssembler() {
        val a = FrameAssembler()
        val frames = a.feed(packets(1, jpeg, 5, angle = 10) + packets(2, jpeg, 3, angle = 20))
        assertEquals(listOf(10, 20), frames.map { it.angle })
        assertEquals(2, a.done)
    }
}
