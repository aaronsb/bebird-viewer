// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeaconTest {
    @Test fun parsesTheReadmeExample() {
        val json = """{"brand":"bebird","model":"ES","mac":"C8:47:8C:01:02:03","ssid":"bebird-ES-XXXXXX","password":"MTIzNDU2Nzg=",
 "wifi_encrypt":false,"ipaddr":"192.168.5.1","button":1,"video_on":0,"battery":65636}"""
        assertEquals(Beacon("bebird-ES-XXXXXX", "C8:47:8C:01:02:03", "ES"), Beacon.parse(json.toByteArray()))
    }

    @Test fun respectsLength() {
        val data = """{"brand":"bebird","ssid":"bebird-ES-1"}GARBAGE""".toByteArray()
        assertEquals("bebird-ES-1", Beacon.parse(data, data.size - 7)?.ssid)
    }

    @Test fun ignoresOtherTraffic() {
        assertNull(Beacon.parse("""{"brand":"other","ssid":"x"}""".toByteArray()))
        assertNull(Beacon.parse(byteArrayOf(0x66, 0x3A, 0, 1)))
        assertNull(Beacon.parse(ByteArray(0)))
    }

    @Test fun missingFieldsAreNull() {
        assertEquals(Beacon(null, null, null), Beacon.parse("""{ "brand" : "Bebird" }""".toByteArray()))
    }
}
