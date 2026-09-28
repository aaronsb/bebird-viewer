// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBookTest {
    private val a = "AA:AA:AA:AA:AA:AA"
    private val b = "BB:BB:BB:BB:BB:BB"

    @Test fun seenAddsAndBecomesLast() {
        val book = DeviceBook().seen("bebird-1", a, 100).seen("bebird-2", b, 200)
        assertEquals(2, book.devices.size)
        assertEquals("bebird-2", book.last?.ssid)
        assertEquals(listOf("bebird-2", "bebird-1"), book.sorted.map { it.ssid })
        assertEquals("bebird-1", book.seen("bebird-1", a, 300).last?.ssid)
    }

    @Test fun seenAgainUpdatesInPlaceAndKeepsTheNickname() {
        val book = DeviceBook().seen("bebird-1", a, 100).rename(a, "left ear").seen("bebird-1", a, 500)
        assertEquals(1, book.devices.size)
        assertEquals(KnownDevice("bebird-1", a, "left ear", 500), book.devices.single())
    }

    @Test fun aBssidUpgradesAnSsidOnlyEntry() {
        val book = DeviceBook().seen("bebird-1", null, 100).rename("ssid:bebird-1", "mine").seen("bebird-1", a, 200)
        assertEquals(KnownDevice("bebird-1", a, "mine", 200), book.devices.single())
        assertEquals(a, book.lastKey)
    }

    @Test fun sameSsidDifferentBssidAreTwoScopes() {
        val book = DeviceBook().seen("bebird-ES-1", a, 100).seen("bebird-ES-1", b, 200)
        assertEquals(2, book.devices.size)
        assertEquals(b, book.last?.bssid)
    }

    @Test fun withoutBssidTheLatestSameSsidIsTaken() {
        val book = DeviceBook().seen("bebird-1", a, 100).seen("bebird-1", null, 200)
        assertEquals(KnownDevice("bebird-1", a, null, 200), book.devices.single())
    }

    @Test fun renameBlankClears() {
        val book = DeviceBook().seen("bebird-1", a, 100).rename(a, "x").rename(a, "  ")
        assertNull(book.devices.single().nickname)
        assertEquals("bebird-1", book.devices.single().label)
    }

    @Test fun forgetRemovesAndClearsLast() {
        val book = DeviceBook().seen("bebird-1", a, 100).seen("bebird-2", b, 200).forget(b)
        assertEquals(listOf("bebird-1"), book.devices.map { it.ssid })
        assertNull(book.last)
        assertEquals(a, book.forget(b).seen("bebird-1", a, 300).lastKey)
    }

    @Test fun roundTrip() {
        val book = DeviceBook()
            .seen("bebird-1", a, 100).rename(a, "tab\there, new\nline, back\\slash")
            .seen("bebird 2 \\t", null, 200)
        assertEquals(book, DeviceBook.decode(book.encode()))
    }

    @Test fun decodeTolerance() {
        assertEquals(DeviceBook(), DeviceBook.decode(null))
        assertEquals(DeviceBook(), DeviceBook.decode(""))
        assertEquals(DeviceBook(), DeviceBook.decode("v9\tx\nbebird\t\t\t1\n"))
        val book = DeviceBook.decode("v1\tgone\nbebird-1\t$a\t\t100\nbroken line\n\t\t\t5\n")
        assertEquals(listOf(KnownDevice("bebird-1", a, null, 100)), book.devices)
        assertNull(book.lastKey)  // a last key that matches no device is dropped
    }

    @Test fun matches() {
        val d = KnownDevice("bebird-1", a, null, 0)
        assertTrue(d.matches("bebird-1", a))
        assertTrue(d.matches("renamed", a))
        assertFalse(d.matches("bebird-1", b))
        assertTrue(d.matches("bebird-1", null))
        assertTrue(KnownDevice("bebird-1", null, null, 0).matches("bebird-1", b))
    }
}
