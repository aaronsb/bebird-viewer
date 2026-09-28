// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.band

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class OnceTest {
    @Test fun concurrentCallersComputeOnceAndShare() {
        val once = Once<Any>()
        val computed = AtomicInteger()
        val go = CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<Any>())
        val threads = List(8) {
            thread {
                go.await()
                results += once.get { computed.incrementAndGet(); Thread.sleep(50); Any() }
            }
        }
        go.countDown()
        threads.forEach { it.join() }
        assertEquals(1, computed.get())
        assertEquals(8, results.size)
        assertTrue(results.all { it === results[0] })
    }
}
