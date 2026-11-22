package dev.tg9.tg

import dev.tg9.Fixtures
import dev.tg9.chat.MessageKind
import dev.tg9.chat.messageKind
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTest {
    private fun file(id: Int, size: Long = 0) = TdApi.File().apply {
        this.id = id
        this.size = size
        local = TdApi.LocalFile()
    }

    @Test
    fun photoUsesTheBestSizeAndItsPreview() {
        val content = Fixtures.photo("", 320, 1280, 2560)
        val media = Media.of(content) as Media.Image
        assertEquals(1280, media.file.id)
        assertEquals("image/jpeg", media.mimeType)
        assertEquals(MessageKind.PHOTO, messageKind(content))
    }

    @Test
    fun videoAndVideoMessagePlayWithSound() {
        val video = TdApi.MessageVideo().apply {
            caption = Fixtures.caption("")
            this.video = TdApi.Video().apply { this.video = file(7, 4_300_000); mimeType = "video/mp4"; duration = 12 }
        }
        val v = Media.of(video) as Media.Video
        assertEquals(7, v.file.id)
        assertEquals(false, v.loop)
        assertEquals(false, v.muted)
        assertEquals(MessageKind.VIDEO, messageKind(video))
        assertEquals("[Video 0:12, 4.1 MB]", Content.label(video))

        val note = TdApi.MessageVideoNote().apply { videoNote = TdApi.VideoNote().apply { this.video = file(8); duration = 5 } }
        assertTrue(Media.of(note) is Media.Video)
    }

    @Test
    fun gifIsASilentLoopUnlessItReallyIsAGifFile() {
        fun animation(mime: String) = TdApi.MessageAnimation().apply {
            caption = Fixtures.caption("")
            animation = TdApi.Animation().apply { this.animation = file(9); mimeType = mime }
        }
        val mp4 = Media.of(animation("video/mp4")) as Media.Video
        assertTrue(mp4.loop && mp4.muted)
        assertTrue(Media.of(animation("image/gif")) is Media.Image)
    }

    @Test
    fun stickersByFormat() {
        fun sticker(format: TdApi.StickerFormat, thumb: TdApi.Thumbnail? = null) = TdApi.MessageSticker().apply {
            sticker = TdApi.Sticker().apply { this.format = format; this.sticker = file(10); thumbnail = thumb; emoji = "🙂" }
        }
        assertTrue(Media.of(sticker(TdApi.StickerFormatWebp())) is Media.Image)
        assertTrue((Media.of(sticker(TdApi.StickerFormatWebm())) as Media.Video).loop)
        // An animated (Lottie) sticker falls back to its still preview, or to nothing.
        val thumb = TdApi.Thumbnail().apply { format = TdApi.ThumbnailFormatWebp(); this.file = file(11) }
        assertEquals(11, Media.of(sticker(TdApi.StickerFormatTgs(), thumb))!!.file.id)
        assertNull(Media.of(sticker(TdApi.StickerFormatTgs())))
    }

    @Test
    fun documentsOpenOnlyWhenTheyArePicturesOrVideos() {
        fun doc(mime: String, name: String = "x") = TdApi.MessageDocument().apply {
            caption = Fixtures.caption("")
            document = TdApi.Document().apply { document = file(12, 2_400_000); mimeType = mime; fileName = name }
        }
        assertTrue(Media.of(doc("image/png")) is Media.Image)
        assertTrue(Media.of(doc("video/mp4")) is Media.Video)
        assertNull(Media.of(doc("application/pdf")))
        assertEquals(MessageKind.FILE, messageKind(doc("application/pdf")))
        assertEquals(MessageKind.VOICE, messageKind(doc("audio/mpeg")))
        assertEquals("[File: report.pdf, 2.3 MB]", Content.label(doc("application/pdf", "report.pdf")))
        assertEquals("report.pdf", Content.savable(doc("application/pdf", "report.pdf"))!!.fileName)
    }

    @Test
    fun neighboursOnlyForPagedKinds() {
        assertTrue(Media.neighbourFilter(Fixtures.photo()) is TdApi.SearchMessagesFilterPhotoAndVideo)
        assertNull(Media.neighbourFilter(TdApi.MessageSticker()))
        assertNull(Media.neighbourFilter(Fixtures.text("hi")))
    }
}
