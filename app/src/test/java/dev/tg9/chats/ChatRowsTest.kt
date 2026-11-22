package dev.tg9.chats

import dev.tg9.Fixtures
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRowsTest {
    private val names = mapOf(1L to "Anna Smith", 2L to "Boris")

    private fun rows(query: String = "", unreadOnly: Boolean = false, group: Boolean = false) = buildChatRows(
        listOf(
            Fixtures.chat(10, "Anna Smith", Fixtures.message(1, Fixtures.text("hello"), 100, fromUser = 1), unread = 2),
            Fixtures.chat(20, "Book club", Fixtures.message(2, Fixtures.photo(), 200, fromUser = 2)),
            Fixtures.chat(30, "Joanna", Fixtures.message(3, Fixtures.text("see you"), 300, outgoing = true)),
        ),
        query,
        unreadOnly,
        isGroup = { group },
        senderName = { sender -> names[(sender as org.drinkless.tdlib.TdApi.MessageSenderUser).userId].orEmpty() },
    )

    @Test
    fun previewsInPrivateChats() {
        val all = rows()
        assertEquals(listOf("hello", "[Photo]", "You: see you"), all.map { it.preview })
    }

    @Test
    fun groupPreviewsNameTheSender() {
        assertEquals("Anna: hello", rows(group = true)[0].preview)
        assertEquals("Boris: [Photo]", rows(group = true)[1].preview)
    }

    @Test
    fun filterMatchesWordStartsOnly() {
        assertEquals(listOf(10L), rows("an").map { it.chatId })
        assertEquals(listOf(20L), rows("cl").map { it.chatId })
        assertTrue(matches("Joanna", "jo"))
        assertFalse(matches("Joanna", "an"))
    }

    @Test
    fun unreadOnly() {
        val unread = rows(unreadOnly = true)
        assertEquals(listOf(10L), unread.map { it.chatId })
        assertEquals(2, unread[0].unreadCount)
    }

    @Test
    fun timestamps() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 11, 22)
        fun at(day: LocalDate, hour: Int) = LocalDateTime.of(day, java.time.LocalTime.of(hour, 5)).toEpochSecond(zone).toInt()
        assertEquals("09:05", formatChatTimestamp(at(today, 9), today, zone))
        assertEquals("", formatChatTimestamp(0, today, zone))
        // Older than a week: a date, not a weekday.
        assertTrue(formatChatTimestamp(at(today.minusDays(30), 9), today, zone).contains("Oct"))
    }
}
