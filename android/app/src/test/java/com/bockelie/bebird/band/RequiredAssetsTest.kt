// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.band

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The build checks the APK against required-assets.txt (see app/build.gradle.kts); this makes
 * sure that list covers every asset the code opens, and that each exists in the sources.
 */
class RequiredAssetsTest {
    private val listed = File("required-assets.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test fun everyAssetTheCodeOpensIsListed() {
        for (path in BandFonts.ALL) assertTrue("$path not in required-assets.txt", path in listed)
    }

    @Test fun everyListedAssetExists() {
        for (path in listed) assertTrue("$path not in src/main/assets", File("src/main/assets", path).isFile)
    }

    @Test fun noAssetEndsInGz() {
        // the build unpacks ".gz" assets and drops the extension; they'd be missing on device
        val gz = File("src/main/assets").walk().filter { it.isFile && it.name.endsWith(".gz") }.toList()
        assertTrue("rename these (e.g. to .gzip): $gz", gz.isEmpty())
    }
}
