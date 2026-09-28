// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

/**
 * Opening the captures folder in the phone's own file browser (no browser in the app). Pure:
 * it builds the attempts, and the caller says whether each one could be started.
 */
object CaptureFolder {
    const val AUTHORITY = "com.android.externalstorage.documents"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_GET_CONTENT = "android.intent.action.GET_CONTENT"
    const val MIME_TYPE_DIR = "vnd.android.document/directory"  // DocumentsContract.Document.MIME_TYPE_DIR
    const val MIME_TYPE_ROOT = "vnd.android.document/root"      // DocumentsContract.Root.MIME_TYPE_ITEM

    /** One way of opening it: an intent's action, data uri and type. */
    data class Attempt(val action: String, val uri: String?, val type: String?)

    sealed interface Outcome {
        data class Opened(val attempt: Attempt) : Outcome
        /** Nothing could open it: tell the user where the files are. */
        data object Explain : Outcome
        /** No capture has been saved yet, so there is no folder to open. */
        data object Empty : Outcome
    }

    /** DocumentsContract.buildDocumentUri(AUTHORITY, "primary:[dir]"). */
    fun documentUri(dir: String = CaptureNames.FOLDER) = "content://$AUTHORITY/document/${encode("primary:$dir")}"

    /** The primary storage root in the Files app. */
    fun rootUri() = "content://$AUTHORITY/root/primary"

    /**
     * In order: the folder as a directory; the same uri without a type; the Files app at the
     * storage root; any document picker.
     */
    fun attempts(): List<Attempt> = listOf(
        Attempt(ACTION_VIEW, documentUri(), MIME_TYPE_DIR),
        Attempt(ACTION_VIEW, documentUri(), null),
        Attempt(ACTION_VIEW, rootUri(), MIME_TYPE_ROOT),
        Attempt(ACTION_GET_CONTENT, null, "*/*"),
    )

    /**
     * Open the folder: nothing is tried if it doesn't exist yet ([Outcome.Empty]); otherwise the
     * first attempt that [tryStart] manages, or [Outcome.Explain].
     */
    fun open(folderExists: Boolean, tryStart: (Attempt) -> Boolean): Outcome {
        if (!folderExists) return Outcome.Empty
        return attempts().firstOrNull(tryStart)?.let { Outcome.Opened(it) } ?: Outcome.Explain
    }

    /** Uri.encode: everything but letters, digits and -_.!~*'() as UTF-8 %XX. */
    private fun encode(s: String) = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "-_.!~*'()")) append(c.toChar())
            else append('%').append("%02X".format(c))
        }
    }
}
