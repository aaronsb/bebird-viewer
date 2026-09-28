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
        val book = DeviceBook().seen("bebird-1", a, 100, confirmed = true).seen("bebird-2", b, 200, confirmed = true)
        assertEquals(2, book.devices.size)
        assertEquals("bebird-2", book.last?.ssid)
        assertEquals(listOf("bebird-2", "bebird-1"), book.sorted.map { it.ssid })
        assertEquals("bebird-1", book.seen("bebird-1", a, 300).last?.ssid)
    }

    @Test fun seenAgainUpdatesInPlaceAndKeepsTheNickname() {
        val book = DeviceBook().seen("bebird-1", a, 100).rename(a, "left ear").seen("bebird-1", a, 500, confirmed = true)
        assertEquals(KnownDevice("bebird-1", a, "left ear", 500, bssidConfirmed = true), book.devices.single())
    }

    @Test fun aBssidUpgradesAnSsidOnlyEntry() {
        val book = DeviceBook().seen("bebird-1", null, 100).rename("ssid:bebird-1", "mine").seen("bebird-1", a, 200)
        assertEquals(KnownDevice("bebird-1", a, "mine", 200), book.devices.single())
        assertEquals(a, book.lastKey)
    }

    @Test fun twoConfirmedBssidsWithOneSsidAreTwoScopes() {
        val book = DeviceBook().seen("bebird-ES-1", a, 100, confirmed = true).seen("bebird-ES-1", b, 200, confirmed = true)
        assertEquals(2, book.devices.size)
        assertEquals(b, book.last?.bssid)
    }

    @Test fun beaconMacThenRealBssidIsOneScope() {
        // the beacon's MAC first (unconfirmed), then the scan's BSSID once joined exactly (confirmed)
        val book = DeviceBook().seen("bebird-ES-1", a, 100).rename(a, "mine").seen("bebird-ES-1", b, 200, confirmed = true)
        assertEquals(KnownDevice("bebird-ES-1", b, "mine", 200, bssidConfirmed = true), book.devices.single())
        assertEquals(b, book.lastKey)
    }

    @Test fun anUnconfirmedMacNeverReplacesAConfirmedBssid() {
        val book = DeviceBook().seen("bebird-ES-1", a, 100, confirmed = true).seen("bebird-ES-1", b, 200)
        assertEquals(KnownDevice("bebird-ES-1", a, null, 200, bssidConfirmed = true), book.devices.single())
    }

    @Test fun unconfirmedValuesReplaceEachOther() {
        val book = DeviceBook().seen("bebird-ES-1", a, 100).seen("bebird-ES-1", b, 200)
        assertEquals(KnownDevice("bebird-ES-1", b, null, 200), book.devices.single())
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
        val book = DeviceBook().seen("bebird-1", a, 100, confirmed = true).seen("bebird-2", b, 200, confirmed = true).forget(b)
        assertEquals(listOf("bebird-1"), book.devices.map { it.ssid })
        assertNull(book.last)
        assertEquals(a, book.forget(b).seen("bebird-1", a, 300).lastKey)
    }

    @Test fun roundTrip() {
        val book = DeviceBook()
            .seen("bebird-1", a, 100, confirmed = true).rename(a, "tab\there, new\nline, back\\slash")
            .seen("bebird 2 \\t", null, 200)
        assertEquals(book, DeviceBook.decode(book.encode()))
    }

    @Test fun readsVersion1AsUnconfirmed() {
        val book = DeviceBook.decode("v1\t$a\nbebird-1\t$a\tnick\t100\n")
        assertEquals(DeviceBook(listOf(KnownDevice("bebird-1", a, "nick", 100, bssidConfirmed = false)), a), book)
    }

    @Test fun decodeSkipsCorruptRows() {
        assertEquals(DeviceBook(), DeviceBook.decode(null))
        assertEquals(DeviceBook(), DeviceBook.decode(""))
        assertEquals(DeviceBook(), DeviceBook.decode("v9\tx\nbebird\t\t\t1\t0\n"))
        val good = "bebird-1\t$a\t\t100\t1"
        val text = listOf(
            "v2\tgone",
            good,
            "broken line",
            "\t\t\t5\t0",                               // empty SSID
            "bebird-${"x".repeat(30)}\t\t\t5\t0",       // SSID over 32 bytes
            "bebird-2\tnot-a-mac\t\t5\t0",              // bad BSSID
            "bebird-3\taa:bb:cc:dd:ee:ff\t\t5\t0",      // BSSID not in canonical form
            "bebird-4\t\t\tsoon\t0",                    // bad time
            "bebird-5\t\t\t-1\t0",                      // negative time
            "bebird-6\t\t\t5\tyes",                     // bad flag
            "bebird-7\t\t\t5",                          // v1 row in a v2 book
            "bebird-8\t\t\t5\t0\textra",                // extra column
            good,                                       // duplicate key
        ).joinToString("\n")
        val book = DeviceBook.decode(text)
        assertEquals(listOf(KnownDevice("bebird-1", a, null, 100, bssidConfirmed = true)), book.devices)
        assertNull(book.lastKey)  // a last key that matches no device is dropped
    }

    @Test fun confirmedNeedsABssid() {
        assertFalse(DeviceBook.decode("v2\t\nbebird-1\t\t\t5\t1\n").devices.single().bssidConfirmed)
    }

    @Test fun matches() {
        val confirmed = KnownDevice("bebird-1", a, null, 0, bssidConfirmed = true)
        assertTrue(confirmed.matches("bebird-1", a))
        assertTrue(confirmed.matches("renamed", a))
        assertFalse(confirmed.matches("bebird-1", b))
        assertTrue(confirmed.matches("bebird-1", null))
        // an unconfirmed BSSID may be the beacon MAC, so a scan's different BSSID still matches by SSID
        val unconfirmed = KnownDevice("bebird-1", a, null, 0)
        assertTrue(unconfirmed.matches("bebird-1", b))
        assertFalse(unconfirmed.matches("bebird-2", a))
        assertTrue(KnownDevice("bebird-1", null, null, 0).matches("bebird-1", b))
    }
}
