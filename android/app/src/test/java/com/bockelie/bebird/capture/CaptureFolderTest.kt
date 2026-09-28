// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import com.bockelie.bebird.capture.CaptureFolder.Plan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class CaptureFolderTest {
    private val root = "content://com.android.externalstorage.documents/document/primary%3APictures%2FBebird"
    private val today = LocalDate.of(2026, 9, 28)

    @Test fun documentUrisAreWhatDocumentsContractBuilds() {
        // DocumentsContract.buildDocumentUri(authority, "primary:Pictures/Bebird")
        assertEquals(root, CaptureFolder.documentUri("Pictures/Bebird"))
        assertEquals("$root%2F2026-09-28", CaptureFolder.documentUri(CaptureNames.folder(today)))
        assertEquals("content://com.android.externalstorage.documents/document/primary%3AA%20b%2F%C3%A9-_.!~*'()",
            CaptureFolder.documentUri("A b/é-_.!~*'()"))
    }

    @Test fun pickerOpensInTodaysFolderWhenItHasCaptures() {
        val asked = mutableListOf<String>()
        assertEquals(Plan.Pick("$root%2F2026-09-28"), CaptureFolder.plan(true, today) { asked += it; true })
        assertEquals(listOf("Pictures/Bebird/2026-09-28"), asked)
    }

    @Test fun elseAtTheRoot() {
        assertEquals(Plan.Pick(root), CaptureFolder.plan(true, today) { false })
    }

    @Test fun noCapturesAtAllMeansNothingToBrowse() {
        var asked = 0
        assertEquals(Plan.Empty, CaptureFolder.plan(false, today) { asked++; true })
        assertEquals(0, asked)
    }

    @Test fun thePickerListsImagesAndVideos() {
        assertArrayEquals(arrayOf("image/*", "video/*"), CaptureFolder.MIME_TYPES)
    }

    @Test fun dayFoldersByLocalDate() {
        assertEquals("Pictures/Bebird", CaptureNames.ROOT)
        assertEquals("Pictures/Bebird/2026-09-28", CaptureNames.folder(today))
        assertEquals("Pictures/Bebird/2026-01-02", CaptureNames.folder(LocalDate.of(2026, 1, 2)))
    }

    @Test fun aroundMidnightTheFolderFollowsTheCaptureTime() {
        // the folder and the file name come from the same local time, so they always agree
        val before = LocalDateTime.of(2026, 9, 28, 23, 59, 59)
        val after = LocalDateTime.of(2026, 9, 29, 0, 0, 0)
        assertEquals("Pictures/Bebird/2026-09-28", CaptureNames.folder(before.toLocalDate()))
        assertEquals("bebird-20260928-235959.jpg", CaptureNames.still(before))
        assertEquals("Pictures/Bebird/2026-09-29", CaptureNames.folder(after.toLocalDate()))
        assertEquals("bebird-20260929-000000.jpg", CaptureNames.still(after))
        // New Year's Eve into January
        assertEquals("Pictures/Bebird/2027-01-01", CaptureNames.folder(LocalDateTime.of(2027, 1, 1, 0, 0, 1).toLocalDate()))
    }
}
