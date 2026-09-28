// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.devices

import android.content.Context

/** Keeps the [DeviceBook] in the app's private SharedPreferences (not backed up: allowBackup is off). */
class DeviceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("devices", Context.MODE_PRIVATE)

    fun load(): DeviceBook = DeviceBook.decode(prefs.getString(KEY, null))

    fun save(book: DeviceBook) = prefs.edit().putString(KEY, book.encode()).apply()

    private companion object {
        const val KEY = "book"
    }
}
