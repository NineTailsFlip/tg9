package dev.tg9.chat

import dev.tg9.Fixtures
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRowsTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 22)

    private fun at(day: LocalDate, hour: Int) = LocalDateTime.of(day, LocalTime.of(hour, 0)).toEpochSecond(zone).toInt()

    private fun context(group: Boolean = false, readOutbox: Long = 0, loaded: Map<Long, TdApi.Message> = emptyMap()) = ChatContext(
        showSenders = group,
        lastReadOutboxId = readOutbox,
        senderName = { "Anna" },
        findMessage = { loaded[it] },
    )

    private val messages = listOf(
        Fixtures.message(3, Fixtures.text("today"), at(today, 9)),
        Fixtures.message(1, Fixtures.text("old"), at(today.minusDays(1), 8)),
        Fixtures.message(2, Fixtures.text("mine"), at(today.minusDays(1), 9), outgoing = true),
    )

    @Test
    fun sortedWithDayHeaders() {
        val rows = buildMessageRows(messages, context(), today, zone)
        assertEquals(
            listOf("YESTERDAY", "old", "mine", "TODAY", "today"),
            rows.map { if (it is MessageRow.DayHeader) it.label else (it as MessageRow.Entry).body },
        )
    }

    @Test
    fun outgoingStatusFollowsReadOutbox() {
        val sent = buildMessageRows(messages, context(readOutbox = 1), today, zone)
        val read = buildMessageRows(messages, context(readOutbox = 2), today, zone)
        assertEquals(SendStatus.SENT, (sent[2] as MessageRow.Entry).status)
        assertEquals(SendStatus.READ, (read[2] as MessageRow.Entry).status)
        assertEquals(SendStatus.NONE, (read[1] as MessageRow.Entry).status)
    }

    @Test
    fun sendersOnlyInGroupsAndNeverOnOwnMessages() {
        val rows = buildMessageRows(messages, context(group = true), today, zone)
        assertEquals("Anna", (rows[1] as MessageRow.Entry).sender)
        assertEquals("", (rows[2] as MessageRow.Entry).sender)
        assertEquals("", (buildMessageRows(messages, context(), today, zone)[1] as MessageRow.Entry).sender)
    }

    @Test
    fun replyLineQuotesTheOriginal() {
        val original = messages[1]
        val reply = Fixtures.message(4, Fixtures.text("yes"), at(today, 10)).apply {
            replyTo = TdApi.MessageReplyToMessage().apply { messageId = 1 }
        }
        val rows = buildMessageRows(listOf(original, reply), context(loaded = mapOf(1L to original)), today, zone)
        assertEquals("↪ Anna: old", (rows.last() as MessageRow.Entry).replyTo)
    }

    @Test
    fun dayJumps() {
        val rows = buildMessageRows(messages, context(), today, zone)
        // From "today" (index 4) back to the first message of yesterday (index 1), and forward again.
        assertEquals(1, dayJumpIndex(rows, 4, -1))
        assertEquals(4, dayJumpIndex(rows, 2, 1))
        assertNull(dayJumpIndex(rows, 1, -1))
        assertNull(dayJumpIndex(rows, 4, 1))
    }

    @Test
    fun kinds() {
        val rows = buildMessageRows(
            listOf(Fixtures.message(1, Fixtures.photo(), 0), Fixtures.message(2, Fixtures.voice(3), 1)),
            context(), today, zone,
        )
        assertTrue(rows.filterIsInstance<MessageRow.Entry>().map { it.kind } == listOf(MessageKind.PHOTO, MessageKind.VOICE))
    }
}
