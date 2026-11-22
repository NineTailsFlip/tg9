package dev.tg9.tg

import org.drinkless.tdlib.TdApi

/** What the full-screen viewer shows for a message: a picture or a video, the file holding it, and
 *  the tiny inline preview Telegram sends along so there's something on screen while the real
 *  file downloads. Pure, like [Content]. */
sealed interface Media {
    val file: TdApi.File
    val minithumbnail: TdApi.Minithumbnail?
    val mimeType: String

    /** A photo, a still or animated sticker, a GIF that really is a .gif, or an image sent as a
     *  file. Animated GIF/WebP play through the platform's own decoder. */
    data class Image(
        override val file: TdApi.File,
        override val minithumbnail: TdApi.Minithumbnail?,
        override val mimeType: String,
    ) : Media

    /** A video, a round video message, a GIF (Telegram sends those as silent looping MP4s), a
     *  video sticker, or a video sent as a file. */
    data class Video(
        override val file: TdApi.File,
        override val minithumbnail: TdApi.Minithumbnail?,
        override val mimeType: String,
        val durationSeconds: Int,
        val loop: Boolean,
        val muted: Boolean,
    ) : Media

    companion object {
        /** The largest photo worth fetching: enough for a 2.5x zoom on a ~320 px screen. */
        const val MAX_PHOTO_SIDE = 1280

        /** Image types the platform decoder opens (ImageDecoder, Android 9+). */
        private val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/heic", "image/heif", "image/bmp")

        /** Video containers MediaPlayer plays on every Android TG9 runs on. */
        private val VIDEO_TYPES = setOf("video/mp4", "video/webm", "video/3gpp", "video/x-matroska", "video/quicktime")

        fun of(content: TdApi.MessageContent?): Media? = when (content) {
            is TdApi.MessagePhoto -> Content.bestPhotoSize(content.photo, MAX_PHOTO_SIDE)?.let {
                Image(it.photo, content.photo.minithumbnail, "image/jpeg")
            }
            is TdApi.MessageVideo -> content.video.let {
                Video(it.video, it.minithumbnail, it.mimeType.ifBlank { "video/mp4" }, it.duration, loop = false, muted = false)
            }
            is TdApi.MessageVideoNote -> content.videoNote.let {
                Video(it.video, it.minithumbnail, "video/mp4", it.duration, loop = false, muted = false)
            }
            is TdApi.MessageAnimation -> content.animation.let {
                if (it.mimeType == "image/gif") {
                    Image(it.animation, it.minithumbnail, it.mimeType)
                } else {
                    Video(it.animation, it.minithumbnail, it.mimeType.ifBlank { "video/mp4" }, it.duration, loop = true, muted = true)
                }
            }
            is TdApi.MessageSticker -> sticker(content.sticker)
            is TdApi.MessageDocument -> content.document.let {
                val type = it.mimeType.lowercase()
                when (type) {
                    in IMAGE_TYPES -> Image(it.document, it.minithumbnail, type)
                    in VIDEO_TYPES -> Video(it.document, it.minithumbnail, type, 0, loop = false, muted = false)
                    else -> null
                }
            }
            else -> null
        }

        /** A WebP sticker is a picture and a WebM one a silent looping video. A TGS sticker is a
         *  Lottie animation, which needs a renderer TG9 doesn't carry, so its still preview stands
         *  in for it. */
        private fun sticker(sticker: TdApi.Sticker): Media? = when (sticker.format) {
            is TdApi.StickerFormatWebp -> Image(sticker.sticker, null, "image/webp")
            is TdApi.StickerFormatWebm -> Video(sticker.sticker, null, "video/webm", 0, loop = true, muted = true)
            else -> sticker.thumbnail?.let { thumb ->
                when (thumb.format) {
                    is TdApi.ThumbnailFormatWebp -> Image(thumb.file, null, "image/webp")
                    is TdApi.ThumbnailFormatPng -> Image(thumb.file, null, "image/png")
                    is TdApi.ThumbnailFormatJpeg -> Image(thumb.file, null, "image/jpeg")
                    is TdApi.ThumbnailFormatWebm -> Video(thumb.file, null, "video/webm", 0, loop = true, muted = true)
                    else -> null
                }
            }
        }

        /** Which of TDLib's search filters finds this message's neighbours for Up/Down in the viewer,
         *  or null where paging through "the next one like it" means nothing (a sticker, a file). */
        fun neighbourFilter(content: TdApi.MessageContent?): TdApi.SearchMessagesFilter? = when (content) {
            is TdApi.MessagePhoto, is TdApi.MessageVideo -> TdApi.SearchMessagesFilterPhotoAndVideo()
            is TdApi.MessageAnimation -> TdApi.SearchMessagesFilterAnimation()
            is TdApi.MessageVideoNote -> TdApi.SearchMessagesFilterVideoNote()
            else -> null
        }
    }
}
