package dev.tg9.chat

import dev.tg9.tg.updates
import org.drinkless.tdlib.TdApi

/** One chat's loaded messages, what TDLib's updates do to them, and which of them TDLib has
 *  already been told were seen. The bookkeeping half of [ChatActivity], kept apart so it can be
 *  tested without a screen. Main thread, like everything that hangs off [dev.tg9.tg.Tg]. */
class ChatTimeline(val chatId: Long) {

    /** What an update changed, and so what the screen redoes. */
    enum class Change {
        NONE,
        /** The rows: a message's content, send state or read ticks. */
        ROWS,
        /** A new message from someone else. */
        NEW_INCOMING,
        /** A new message of one's own (sent from here or another device). */
        NEW_OUTGOING,
        /** The title or the other person's status above the messages. */
        HEADER,
    }

    private val messages = HashMap<Long, TdApi.Message>()

    /** Ids TDLib has been sent in viewMessages: each is sent once, not with every new message. */
    private val viewed = HashSet<Long>()

    val isEmpty: Boolean get() = messages.isEmpty()
    val size: Int get() = messages.size

    operator fun get(id: Long): TdApi.Message? = messages[id]

    operator fun contains(id: Long): Boolean = id in messages

    /** The oldest loaded message's id, where the next page of history starts; 0 with none. */
    fun oldestId(): Long = messages.keys.minOrNull() ?: 0L

    fun all(): List<TdApi.Message> = messages.values.toList()

    /** Adds a page of history; returns how many of its messages weren't loaded yet. */
    fun addHistory(page: Array<TdApi.Message>): Int {
        var fresh = 0
        for (message in page) {
            if (message.chatId != chatId || message.id in messages) continue
            messages[message.id] = message
            fresh++
        }
        return fresh
    }

    fun apply(update: TdApi.Update): Change = when (update) {
        is TdApi.UpdateNewMessage -> if (update.message.chatId != chatId) {
            Change.NONE
        } else {
            messages[update.message.id] = update.message
            if (update.message.isOutgoing) Change.NEW_OUTGOING else Change.NEW_INCOMING
        }
        is TdApi.UpdateMessageSendSucceeded -> replace(update.oldMessageId, update.message)
        is TdApi.UpdateMessageSendFailed -> replace(update.oldMessageId, update.message)
        is TdApi.UpdateMessageContent -> if (update.chatId != chatId) {
            Change.NONE
        } else {
            messages[update.messageId]?.let { it.content = update.newContent; Change.ROWS } ?: Change.NONE
        }
        is TdApi.UpdateMessageEdited -> if (update.chatId != chatId) {
            Change.NONE
        } else {
            messages[update.messageId]?.let { it.editDate = update.editDate; Change.ROWS } ?: Change.NONE
        }
        // Not permanent means "dropped from TDLib's cache", not deleted: the message stays.
        is TdApi.UpdateDeleteMessages -> if (update.chatId != chatId || !update.isPermanent) {
            Change.NONE
        } else {
            var removed = false
            update.messageIds.forEach { id ->
                if (messages.remove(id) != null) removed = true
                viewed.remove(id)
            }
            if (removed) Change.ROWS else Change.NONE
        }
        is TdApi.UpdateChatReadOutbox -> if (update.chatId == chatId) Change.ROWS else Change.NONE
        is TdApi.UpdateChatTitle -> if (update.chatId == chatId) Change.HEADER else Change.NONE
        is TdApi.UpdateUser, is TdApi.UpdateUserStatus -> Change.HEADER
        else -> Change.NONE
    }

    /** A message that was being sent, under its new permanent id. */
    private fun replace(oldId: Long, message: TdApi.Message): Change {
        if (message.chatId != chatId) return Change.NONE
        messages.remove(oldId)
        messages[message.id] = message
        // A message one sent oneself was seen already.
        if (viewed.remove(oldId)) viewed += message.id
        return Change.ROWS
    }

    /** The loaded messages TDLib hasn't been told were seen, now counted as told. Empty when
     *  there's nothing new: no request needed. */
    fun takeUnviewed(): LongArray {
        val fresh = messages.keys.filter { it !in viewed }
        viewed += fresh
        return fresh.toLongArray()
    }

    companion object {
        /** Every type [apply] reads. */
        val UPDATES = updates(
            TdApi.UpdateNewMessage::class,
            TdApi.UpdateMessageSendSucceeded::class,
            TdApi.UpdateMessageSendFailed::class,
            TdApi.UpdateMessageContent::class,
            TdApi.UpdateMessageEdited::class,
            TdApi.UpdateDeleteMessages::class,
            TdApi.UpdateChatReadOutbox::class,
            TdApi.UpdateChatTitle::class,
            TdApi.UpdateUser::class,
            TdApi.UpdateUserStatus::class,
        )
    }
}
