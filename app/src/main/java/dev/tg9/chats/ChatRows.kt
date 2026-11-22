package dev.tg9.chats

import dev.tg9.tg.Content
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import life.furrer.nine.core.util.DateFmt
import org.drinkless.tdlib.TdApi

/** One chat-list row, already reduced to strings: the renderer only lays these out. */
data class ChatRow(
    val chatId: Long,
    val title: String,
    val preview: String,
    val date: Int,
    val unreadCount: Int,
    val markedUnread: Boolean,
    val muted: Boolean,
    val pinned: Boolean,
    val hasDraft: Boolean,
) {
    val hasUnread: Boolean get() = unreadCount > 0 || markedUnread
}

/** [senderName] resolves who sent a message, for the "Name: …" prefix in groups. Passed in (rather
 *  than read from TgStore) so this stays a pure function the tests can drive. */
fun buildChatRows(
    chats: List<TdApi.Chat>,
    query: String,
    unreadOnly: Boolean,
    isGroup: (TdApi.Chat) -> Boolean,
    senderName: (TdApi.MessageSender) -> String,
    localDrafts: Set<Long> = emptySet(),
): List<ChatRow> {
    val needle = query.trim()
    return chats
        .filter { needle.isEmpty() || matches(it.title, needle) }
        .map { chat -> toRow(chat, isGroup(chat), senderName, chat.id in localDrafts) }
        .filter { !unreadOnly || it.hasUnread }
}

private fun toRow(chat: TdApi.Chat, group: Boolean, senderName: (TdApi.MessageSender) -> String, localDraft: Boolean): ChatRow {
    val last = chat.lastMessage
    val summary = Content.summary(last?.content)
    val preview = when {
        last == null -> ""
        last.isOutgoing && !last.isChannelPost -> "You: $summary"
        group -> senderName(last.senderId).let { if (it.isBlank()) summary else "${firstName(it)}: $summary" }
        else -> summary
    }
    val position = chat.positions.firstOrNull { it.list is TdApi.ChatListMain }
    return ChatRow(
        chatId = chat.id,
        title = chat.title.ifBlank { "Deleted account" },
        preview = preview,
        date = last?.date ?: 0,
        unreadCount = chat.unreadCount,
        markedUnread = chat.isMarkedAsUnread,
        muted = isMuted(chat.notificationSettings),
        pinned = position?.isPinned == true,
        hasDraft = localDraft || chat.draftMessage != null,
    )
}

/** A chat following the default (use_default_mute_for) is shown as not muted: the default's own
 *  value lives in the scope settings, and TDLib ignores this chat's mute_for in that case. */
fun isMuted(settings: TdApi.ChatNotificationSettings?): Boolean =
    settings != null && !settings.useDefaultMuteFor && settings.muteFor > 0

private fun firstName(name: String): String = name.substringBefore(' ')

/** A word-start match, case-insensitive: "an" finds "Anna" and "Book club Anna", not "Joanna" —
 *  the same rule as typing a name into any Nine list. */
fun matches(title: String, query: String): Boolean {
    val t = title.lowercase(Locale.getDefault())
    val q = query.lowercase(Locale.getDefault())
    if (t.startsWith(q)) return true
    return t.split(' ', '-', '_', '.', '(', ')').any { it.startsWith(q) }
}

/** Today: the time. Within the past week: the weekday. Otherwise: the date. */
fun formatChatTimestamp(unixSeconds: Int, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
    if (unixSeconds <= 0) return ""
    val millis = unixSeconds * 1000L
    val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    return when {
        day == today -> DateFmt.formatTime(millis, zone)
        day.isAfter(today.minusDays(7)) -> day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
        // Built per call rather than cached in a static, so a locale change applies without a
        // restart; a row only formats its date when its content changed.
        else -> day.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
    }
}
