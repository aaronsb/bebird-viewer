// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.settings

import android.content.Context

/** A small key-value store; the seam tests replace. */
interface KeyValue {
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
}

class MemoryKeyValue : KeyValue {
    private val map = HashMap<String, Any>()
    override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun getString(key: String, default: String) = map[key] as? String ?: default
    override fun putString(key: String, value: String) { map[key] = value }
}

/** The app's private SharedPreferences (not backed up: allowBackup is off). */
class PrefsKeyValue(context: Context) : KeyValue {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    override fun getString(key: String, default: String) = prefs.getString(key, default) ?: default
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What the viewer remembers between runs, like ~/.config/bebird/state.json on the desktop. */
class Settings(private val kv: KeyValue) {

    var light: Int
        get() = kv.getInt("light", 100).coerceIn(0, 100)
        set(v) = kv.putInt("light", v)
    var lightBeforeOff: Int
        get() = kv.getInt("light_before_off", 100).coerceIn(1, 100)
        set(v) = kv.putInt("light_before_off", v)
    var autoRotate: Boolean
        get() = kv.getBoolean("auto_rotate", true)
        set(v) = kv.putBoolean("auto_rotate", v)
    var trim: Int
        get() = kv.getInt("trim", 0).coerceIn(-180, 180)
        set(v) = kv.putInt("trim", v)
    /**
     * The overlay: the status band under the image and a hair-thin circle at the image circle's
     * edge, on screen and (with #15) in saved stills. Stored under its first key, "band"; an
     * older separate "circle" setting is ignored.
     */
    var overlay: Boolean
        get() = kv.getBoolean("band", true)
        set(v) = kv.putBoolean("band", v)
    /** The free-text label shown in the band and saved in files; empty when cleared. */
    var label: String
        get() = Labels.limit(kv.getString("label", ""))
        set(v) = kv.putString("label", Labels.limit(v.trim()))
    var theme: ThemeMode
        get() = ThemeMode.entries.firstOrNull { it.name == kv.getString("theme", "") } ?: ThemeMode.SYSTEM
        set(v) = kv.putString("theme", v.name)
}
