// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import com.bockelie.bebird.R
import java.io.File

/** The app's strings as written in values/strings.xml (JVM tests run in the module directory). */
object AppStrings {
    private val strings: Map<String, String> by lazy {
        val xml = File("src/main/res/values/strings.xml").readText()
        Regex("""<string name="([^"]+)">(.*?)</string>""").findAll(xml).associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    }

    /** The text of string resource [id]. */
    fun text(id: Int): String {
        val name = R.string::class.java.fields.first { it.getInt(null) == id }.name
        return strings.getValue(name)
    }
}
