// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import com.bockelie.bebird.capture.CaptureFolder.Attempt
import com.bockelie.bebird.capture.CaptureFolder.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureFolderTest {
    private val folder = "content://com.android.externalstorage.documents/document/primary%3APictures%2FBebird"

    @Test fun theFolderUriIsWhatDocumentsContractBuilds() {
        // DocumentsContract.buildDocumentUri(authority, "primary:Pictures/Bebird")
        assertEquals(folder, CaptureFolder.documentUri())
        assertEquals("content://com.android.externalstorage.documents/document/primary%3AA%20b%2F%C3%A9-_.!~*'()",
            CaptureFolder.documentUri("A b/é-_.!~*'()"))
    }

    @Test fun attemptsInOrder() {
        assertEquals(
            listOf(
                Attempt(CaptureFolder.ACTION_VIEW, folder, "vnd.android.document/directory"),
                Attempt(CaptureFolder.ACTION_VIEW, folder, null),
                Attempt(CaptureFolder.ACTION_VIEW, "content://com.android.externalstorage.documents/root/primary", "vnd.android.document/root"),
                Attempt(CaptureFolder.ACTION_GET_CONTENT, null, "*/*"),
            ),
            CaptureFolder.attempts(),
        )
    }

    @Test fun theFirstThatStartsWins() {
        val tried = mutableListOf<Attempt>()
        val all = CaptureFolder.attempts()
        for (works in all.indices) {
            tried.clear()
            val outcome = CaptureFolder.open(folderExists = true) { tried += it; it == all[works] }
            assertEquals(Outcome.Opened(all[works]), outcome)
            assertEquals(all.take(works + 1), tried)  // earlier ones tried first, later ones never
        }
    }

    @Test fun nothingStartsSoExplain() {
        val tried = mutableListOf<Attempt>()
        assertEquals(Outcome.Explain, CaptureFolder.open(folderExists = true) { tried += it; false })
        assertEquals(CaptureFolder.attempts(), tried)
    }

    @Test fun noFolderYetTriesNothing() {
        var tried = 0
        assertEquals(Outcome.Empty, CaptureFolder.open(folderExists = false) { tried++; true })
        assertEquals(0, tried)
    }

    @Test fun oneFolderForEverything() {
        assertEquals("Pictures/Bebird", CaptureNames.FOLDER)
    }
}
