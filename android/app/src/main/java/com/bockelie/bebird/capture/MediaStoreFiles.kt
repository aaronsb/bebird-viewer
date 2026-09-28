// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log

/**
 * Saving into the shared Pictures/Movies collections through MediaStore: no storage permission
 * on Android 10+. Entries are pending (hidden) until complete, and removed if writing fails.
 */
class MediaStoreFiles(private val resolver: ContentResolver) {
    enum class Kind(val collection: Uri, val mime: String, val dir: String) {
        // Video in Pictures/ too (allowed for the video collection on Android 10+): one folder.
        STILL(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "image/jpeg", CaptureNames.FOLDER),
        VIDEO(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "video/mp4", CaptureNames.FOLDER),
    }

    /** A new pending entry named [name], taken now (so galleries sort it by capture time). */
    fun create(kind: Kind, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
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

    /**
     * Remove this app's entries still pending in Pictures/Bebird: left by a
     * capture interrupted by the process dying (Android 10 never expires them). Other apps'
     * pending entries aren't visible to us, so only ours can match.
     */
    fun sweepPending(): Int = Kind.entries.sumOf { kind ->
        val where = "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("${kind.dir}%")
        val uris = mutableListOf<Uri>()
        try {
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                resolver.query(kind.collection, arrayOf(MediaStore.MediaColumns._ID), Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }, null)
            } else {
                @Suppress("DEPRECATION")  // the Android 10 way to see pending entries
                resolver.query(MediaStore.setIncludePending(kind.collection), arrayOf(MediaStore.MediaColumns._ID), where, args, null)
            }
            cursor?.use { while (it.moveToNext()) uris += ContentUris.withAppendedId(kind.collection, it.getLong(0)) }
        } catch (e: Exception) {
            Log.w("BebirdSpike", "couldn't look for leftover ${kind.name.lowercase()} entries", e)
        }
        uris.forEach(::discard)
        uris.size
    }

    /** Whether the captures folder holds any of this app's finished captures (so it exists). */
    fun hasCaptures(): Boolean = Kind.entries.any { kind ->
        runCatching {
            resolver.query(
                kind.collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("${kind.dir}%"), null,
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
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
