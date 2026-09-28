// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore

/**
 * Saving into the shared Pictures/Movies collections through MediaStore: no storage permission
 * on Android 10+. Entries are pending (hidden) until complete, and removed if writing fails.
 */
class MediaStoreFiles(private val resolver: ContentResolver) {
    enum class Kind(val collection: Uri, val mime: String, val dir: String) {
        STILL(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "image/jpeg", CaptureNames.PICTURES),
        VIDEO(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "video/mp4", CaptureNames.MOVIES),
    }

    /** A new pending entry named [name]. */
    fun create(kind: Kind, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, kind.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, kind.dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(kind.collection, values) ?: error("MediaStore refused $name")
    }

    fun openForWriting(uri: Uri): ParcelFileDescriptor =
        resolver.openFileDescriptor(uri, "rw") ?: error("can't open $uri")

    /** Make [uri] visible to other apps. */
    fun publish(uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    fun discard(uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    /** Write [bytes] as a new [kind] entry named [name]; returns its uri. */
    fun write(kind: Kind, name: String, bytes: ByteArray): Uri {
        val uri = create(kind, name)
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("can't write $uri")
            publish(uri)
            return uri
        } catch (e: Exception) {
            discard(uri)
            throw e
        }
    }
}
