package dev.tg9.chat

import dev.tg9.tg.Content
import dev.tg9.tg.Media
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import life.furrer.nine.core.util.DateFmt
import org.drinkless.tdlib.TdApi

enum class SendStatus { NONE, PENDING, SENT, READ, FAILED }

/** What OK does on a message, and so what the hint bar offers: PHOTO opens a picture (a photo, a
 *  sticker, an image sent as a file), VIDEO plays one (a video, a round video message, a GIF),
 *  VOICE plays in place (voice messages and music), FILE can be saved to the phone. */
enum class MessageKind { TEXT, PHOTO, VIDEO, VOICE, FILE, OTHER }

fun messageKind(content: TdApi.MessageContent?): MessageKind {
    if (content is TdApi.MessageText) return MessageKind.TEXT
    if (content is TdApi.MessagePhoto) return MessageKind.PHOTO
    return when (Media.of(content)) {
        is Media.Image -> MessageKind.PHOTO
        is Media.Video -> MessageKind.VIDEO
        null -> when {
            Content.playable(content) != null -> MessageKind.VOICE
            Content.savable(content) != null -> MessageKind.FILE
            else -> MessageKind.OTHER
        }
    }
}

sealed interface MessageRow {
    fun isSelectable(): Boolean

    data class DayHeader(val label: String) : MessageRow {
        override fun isSelectable() = false
    }

    data class Entry(
        val id: Long,
        val outgoing: Boolean,
        /** Shown above incoming messages in groups; empty in private chats and on your own. */
        val sender: String,
        val body: String,
        /** "↪ Anna: text" when this replies to something, else empty. */
        val replyTo: String,
        val date: Int,
        val status: SendStatus,
        val kind: MessageKind,
        val edited: Boolean,
    ) : MessageRow {
        override fun isSelectable() = true
    }
}

/** Everything the row builder needs to know about the chat, passed in so [buildMessageRows]
 *  stays pure. */
data class ChatContext(
    val showSenders: Boolean,
    val lastReadOutboxId: Long,
    val senderName: (TdApi.MessageSender) -> String,
    /** Resolves a replied-to message among those already loaded; null when it isn't. */
    val findMessage: (Long) -> TdApi.Message?,
)

/** Oldest at the top, with a day header above each day's first message — M9's thread layout. */
fun buildMessageRows(
    messages: List<TdApi.Message>,
    context: ChatContext,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): List<MessageRow> {
    val rows = ArrayList<MessageRow>(messages.size + 8)
    var lastDay: LocalDate? = null
    for (message in messages.sortedBy { it.id }) {
        val day = Instant.ofEpochSecond(message.date.toLong()).atZone(zone).toLocalDate()
        if (day != lastDay) {
            rows += MessageRow.DayHeader(DateFmt.dayLabel(day, today))
            lastDay = day
        }
        rows += toEntry(message, context)
    }
    return rows
}

private fun toEntry(message: TdApi.Message, context: ChatContext): MessageRow.Entry {
    val outgoing = message.isOutgoing
    return MessageRow.Entry(
        id = message.id,
        outgoing = outgoing,
        sender = if (context.showSenders && !outgoing) context.senderName(message.senderId) else "",
        body = Content.body(message.content).ifEmpty { " " },
        replyTo = replyLine(message, context),
        date = message.date,
        status = when {
            !outgoing -> SendStatus.NONE
            message.sendingState is TdApi.MessageSendingStateFailed -> SendStatus.FAILED
            message.sendingState is TdApi.MessageSendingStatePending -> SendStatus.PENDING
            message.id <= context.lastReadOutboxId -> SendStatus.READ
            else -> SendStatus.SENT
        },
        kind = messageKind(message.content),
        edited = message.editDate > 0,
    )
}

private fun replyLine(message: TdApi.Message, context: ChatContext): String {
    val reply = message.replyTo as? TdApi.MessageReplyToMessage ?: return ""
    val original = context.findMessage(reply.messageId)
    val content = original?.content ?: reply.content
    val who = original?.let { if (it.isOutgoing) "You" else context.senderName(it.senderId) }.orEmpty()
    val text = Content.summary(content).ifEmpty { "message" }
    return if (who.isEmpty()) "↪ $text" else "↪ $who: $text"
}

fun sendStatusGlyph(status: SendStatus): String = when (status) {
    SendStatus.NONE -> ""
    SendStatus.PENDING -> "…"
    SendStatus.SENT -> "✓"
    SendStatus.READ -> "✓✓"
    SendStatus.FAILED -> "! not sent"
}

/** The index of the first message of the day before/after the one [fromIndex] is in. */
fun dayJumpIndex(rows: List<MessageRow>, fromIndex: Int, direction: Int): Int? {
    if (fromIndex !in rows.indices) return null
    var header = fromIndex
    while (header > 0 && rows[header] !is MessageRow.DayHeader) header--
    if (direction < 0) {
        if (header == 0) return null
        var previous = header - 1
        while (previous > 0 && rows[previous] !is MessageRow.DayHeader) previous--
        return (previous + 1).takeIf { it < rows.size && rows[it] is MessageRow.Entry }
    }
    var next = header + 1
    while (next < rows.size && rows[next] !is MessageRow.DayHeader) next++
    return (next + 1).takeIf { it < rows.size }
}
