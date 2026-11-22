package dev.tg9.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/** Copies a downloaded file out of TDLib's private storage into the shared folders, through
 *  MediaStore, which needs no storage permission on Android 10+: pictures to Pictures/TG9, videos
 *  to Movies/TG9, audio to Music/TG9, anything else to Download/TG9.
 *
 *  Blocking: call it off the main thread (core's Async). */
object SaveToPhone {
    /** @return where it went ("Pictures/TG9"), or null when it couldn't be saved. */
    fun save(context: Context, path: String, displayName: String, mimeType: String): String? {
        val (collection, folder) = when {
            mimeType.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
            mimeType.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
            mimeType.startsWith("audio/") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MUSIC
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
        }
        val relativePath = "$folder/TG9"
        val resolver = context.contentResolver
        var uri: Uri? = null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                // Hidden from other apps until the copy is complete.
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> File(path).inputStream().use { it.copyTo(out) } } ?: error("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            relativePath
        } catch (e: Exception) {
            Log.w("TG9", "save to phone", e)
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            null
        }
    }
}
