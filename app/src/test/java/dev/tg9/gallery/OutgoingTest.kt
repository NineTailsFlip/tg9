package dev.tg9.gallery

import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class OutgoingTest {
    private fun photo() = TdApi.InputMessagePhoto()

    @Test
    fun oneItemIsOneMessage() {
        val content = photo()
        val send = Outgoing.sendFunction(7, 0, listOf(content)) as TdApi.SendMessage
        assertEquals(7L, send.chatId)
        assertNull(send.replyTo)
        assertSame(content, send.inputMessageContent)
    }

    @Test
    fun severalAreAnAlbumReplyingWhenAsked() {
        val album = Outgoing.sendFunction(7, 42, listOf(photo(), photo(), photo())) as TdApi.SendMessageAlbum
        assertEquals(7L, album.chatId)
        assertEquals(3, album.inputMessageContents.size)
        assertEquals(42L, (album.replyTo as TdApi.InputMessageReplyToMessage).messageId)
    }

    @Test
    fun photosHalveUntilTheyFit() {
        assertEquals(1, Outgoing.photoSampleSize(2560, 1920))
        assertEquals(2, Outgoing.photoSampleSize(4000, 3000))
        assertEquals(4, Outgoing.photoSampleSize(3000, 8000))
    }
}
