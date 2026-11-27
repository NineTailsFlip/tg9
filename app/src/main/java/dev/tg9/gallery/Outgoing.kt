package dev.tg9.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Size
import dev.g9.kit.GalleryItem
import dev.g9.kit.GalleryStore
import java.io.File
import org.drinkless.tdlib.TdApi

/** Turns a gallery item into what TDLib sends. Blocking: run off the main thread.
 *
 *  A photo is decoded (which applies its EXIF rotation, so a phone held sideways doesn't send it
 *  sideways), halved until its longer side is at most [MAX_PHOTO_SIDE] — about what Telegram keeps
 *  anyway — and re-encoded as JPEG: a 12 MP camera photo goes up as ~1 MB instead of ~5 MB on a
 *  keypad phone's slow connection. A video is sent as it is, with a small thumbnail, since TDLib
 *  makes none and recipients would otherwise see a black square until it loads. */
object Outgoing {
    const val MAX_PHOTO_SIDE = 2560
    private const val JPEG_QUALITY = 87
    private const val THUMB_SIDE = 320
    /** TDLib uploads straight from these files and keeps pointing at them, so they stay a day. */
    private const val KEEP_MS = 24 * 60 * 60 * 1000L

    fun prepare(context: Context, item: GalleryItem): TdApi.InputMessageContent {
        val dir = File(context.cacheDir, "outgoing").apply { mkdirs() }
        deleteOld(dir)
        return if (item.isVideo) video(context, item, dir) else photo(context, item, dir)
    }

    /** One message for one item, an album for several; a reply when [replyTo] isn't 0. */
    fun sendFunction(chatId: Long, replyTo: Long, contents: List<TdApi.InputMessageContent>): TdApi.Function<*> {
        require(contents.isNotEmpty())
        val reply = replyTo.takeIf { it != 0L }?.let { id -> TdApi.InputMessageReplyToMessage().apply { messageId = id } }
        return if (contents.size == 1) {
            TdApi.SendMessage().apply {
                this.chatId = chatId
                this.replyTo = reply
                inputMessageContent = contents.first()
            }
        } else {
            TdApi.SendMessageAlbum(chatId, null, reply, null, contents.toTypedArray())
        }
    }

    /** Halves a [width]×[height] photo until its longer side is at most [MAX_PHOTO_SIDE]. */
    fun photoSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > MAX_PHOTO_SIDE) sample *= 2
        return sample
    }

    private fun photo(context: Context, item: GalleryItem, dir: File): TdApi.InputMessagePhoto {
        val source = ImageDecoder.createSource(context.contentResolver, GalleryStore.contentUri(item))
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(photoSampleSize(info.size.width, info.size.height))
        }
        val out = File(dir, "photo-${item.id}-${System.currentTimeMillis()}.jpg")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        val width = bitmap.width
        val height = bitmap.height
        bitmap.recycle()
        return TdApi.InputMessagePhoto(
            TdApi.InputPhoto(TdApi.InputFileLocal(out.absolutePath), null, null, IntArray(0), width, height),
            TdApi.FormattedText("", emptyArray()),
            false,
            null,
            false,
        )
    }

    private fun video(context: Context, item: GalleryItem, dir: File): TdApi.InputMessageVideo {
        val uri = GalleryStore.contentUri(item)
        // With media access granted, Android 11+ lets the app read the file at its own path; only
        // when it can't (no path, or access to only some photos) is it copied out first.
        val path = item.path?.takeIf { File(it).canRead() } ?: run {
            val extension = item.mimeType.substringAfter('/', "mp4").substringBefore(';').ifBlank { "mp4" }
            val copy = File(dir, "video-${item.id}.$extension")
            context.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                ?: error("can't read the video")
            copy.absolutePath
        }
        val thumbnail = runCatching { context.contentResolver.loadThumbnail(uri, Size(THUMB_SIDE, THUMB_SIDE), null) }
            .getOrNull()
            ?.let { bmp ->
                val file = File(dir, "thumb-${item.id}.jpg")
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                TdApi.InputThumbnail(TdApi.InputFileLocal(file.absolutePath), bmp.width, bmp.height).also { bmp.recycle() }
            }
        return TdApi.InputMessageVideo(
            TdApi.InputVideo(
                TdApi.InputFileLocal(path),
                thumbnail,
                null,
                0,
                IntArray(0),
                (item.durationMs / 1000).toInt(),
                item.width,
                item.height,
                true,
            ),
            TdApi.FormattedText("", emptyArray()),
            false,
            null,
            false,
        )
    }

    private fun deleteOld(dir: File) {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        dir.listFiles { f -> f.lastModified() < cutoff }?.forEach { it.delete() }
    }
}
