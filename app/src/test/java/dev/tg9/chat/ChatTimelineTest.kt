package dev.tg9.chat

import dev.tg9.Fixtures
import dev.tg9.chat.ChatTimeline.Change
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTimelineTest {
    private val chat = 7L
    private val timeline = ChatTimeline(chat)

    private fun message(id: Long, body: String = "m$id", outgoing: Boolean = false, chatId: Long = chat) =
        Fixtures.message(id, Fixtures.text(body), date = id.toInt(), outgoing = outgoing, chatId = chatId)

    @Test
    fun historyPagesCountOnlyWhatsNew() {
        assertEquals(0L, timeline.oldestId())
        assertEquals(2, timeline.addHistory(arrayOf(message(20), message(30))))
        assertEquals(1, timeline.addHistory(arrayOf(message(10), message(20))))
        // The start of the chat: a page of nothing new.
        assertEquals(0, timeline.addHistory(arrayOf(message(10))))
        assertEquals(10L, timeline.oldestId())
        assertEquals(3, timeline.size)
    }

    @Test
    fun anotherChatsMessagesAreIgnored() {
        assertEquals(0, timeline.addHistory(arrayOf(message(1, chatId = 99))))
        assertEquals(Change.NONE, timeline.apply(TdApi.UpdateNewMessage(message(2, chatId = 99))))
        assertEquals(Change.NONE, timeline.apply(TdApi.UpdateChatTitle(99, "x")))
        assertTrue(timeline.isEmpty)
    }

    @Test
    fun newMessagesSayWhoseTheyAre() {
        assertEquals(Change.NEW_INCOMING, timeline.apply(TdApi.UpdateNewMessage(message(1))))
        assertEquals(Change.NEW_OUTGOING, timeline.apply(TdApi.UpdateNewMessage(message(2, outgoing = true))))
        assertEquals(2, timeline.size)
    }

    @Test
    fun aSentMessageTakesItsPermanentId() {
        timeline.apply(TdApi.UpdateNewMessage(message(-5, outgoing = true)))
        assertArrayEquals(longArrayOf(-5), timeline.takeUnviewed())

        val sent = message(500, outgoing = true)
        assertEquals(Change.ROWS, timeline.apply(TdApi.UpdateMessageSendSucceeded(sent, -5)))
        assertNull(timeline[-5])
        assertEquals(sent, timeline[500])
        // Already reported under its temporary id: not again.
        assertEquals(0, timeline.takeUnviewed().size)
    }

    @Test
    fun editsAndNewContentChangeTheLoadedMessage() {
        timeline.addHistory(arrayOf(message(1, "old")))
        assertEquals(Change.ROWS, timeline.apply(TdApi.UpdateMessageContent(chat, 1, Fixtures.text("new"))))
        assertEquals("new", (timeline[1]!!.content as TdApi.MessageText).text.text)
        assertEquals(Change.ROWS, timeline.apply(TdApi.UpdateMessageEdited(chat, 1, 1234, null)))
        assertEquals(1234, timeline[1]!!.editDate)
        // A message that isn't loaded: nothing to redo.
        assertEquals(Change.NONE, timeline.apply(TdApi.UpdateMessageContent(chat, 2, Fixtures.text("x"))))
    }

    @Test
    fun onlyPermanentDeletesRemove() {
        timeline.addHistory(arrayOf(message(1), message(2)))
        assertEquals(Change.NONE, timeline.apply(TdApi.UpdateDeleteMessages(chat, longArrayOf(1), false, true)))
        assertTrue(1L in timeline)
        assertEquals(Change.ROWS, timeline.apply(TdApi.UpdateDeleteMessages(chat, longArrayOf(1), true, false)))
        assertFalse(1L in timeline)
        assertEquals(Change.NONE, timeline.apply(TdApi.UpdateDeleteMessages(chat, longArrayOf(1), true, false)))
    }

    @Test
    fun eachMessageIsReportedSeenOnce() {
        timeline.addHistory(arrayOf(message(1), message(2)))
        assertArrayEquals(longArrayOf(1, 2), timeline.takeUnviewed().sortedArray())
        assertEquals(0, timeline.takeUnviewed().size)

        timeline.apply(TdApi.UpdateNewMessage(message(3)))
        assertArrayEquals(longArrayOf(3), timeline.takeUnviewed())
    }

    @Test
    fun readTicksAndTheHeader() {
        assertEquals(Change.ROWS, timeline.apply(TdApi.UpdateChatReadOutbox(chat, 5)))
        assertEquals(Change.HEADER, timeline.apply(TdApi.UpdateChatTitle(chat, "Mom")))
        assertEquals(Change.HEADER, timeline.apply(TdApi.UpdateUserStatus(1, TdApi.UserStatusOnline(0))))
        assertEquals(Change.NONE, timeline.apply(TdApi.UpdateChatPosition()))
    }

    @Test
    fun everyTypeItReadsIsDeclared() {
        val handled = listOf(
            TdApi.UpdateNewMessage(message(1)),
            TdApi.UpdateMessageSendSucceeded(message(2), 1),
            TdApi.UpdateMessageSendFailed(message(3), 2, TdApi.Error(400, "x")),
            TdApi.UpdateMessageContent(chat, 3, Fixtures.text("x")),
            TdApi.UpdateMessageEdited(chat, 3, 1, null),
            TdApi.UpdateDeleteMessages(chat, longArrayOf(3), true, false),
            TdApi.UpdateChatReadOutbox(chat, 1),
            TdApi.UpdateChatTitle(chat, "t"),
            TdApi.UpdateUser(TdApi.User()),
            TdApi.UpdateUserStatus(1, TdApi.UserStatusOffline(0)),
        )
        handled.forEach { assertTrue(it.javaClass.simpleName, it.javaClass in ChatTimeline.UPDATES) }
        assertEquals(handled.size, ChatTimeline.UPDATES.size)
    }
}
