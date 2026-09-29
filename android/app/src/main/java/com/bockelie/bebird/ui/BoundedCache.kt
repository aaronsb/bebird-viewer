// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

/** A small least-recently-used map holding at most [max] entries. */
class BoundedCache<K, V>(private val max: Int) {
    private val map = object : LinkedHashMap<K, V>(max + 1, 0.75f, true) {
        // `this.size`: the map's own, stated explicitly (an outer `size` once shadowed it)
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) = this.size > max
    }

    val size: Int get() = map.size

    operator fun get(key: K): V? = map[key]

    operator fun set(key: K, value: V) {
        map[key] = value
    }
}
