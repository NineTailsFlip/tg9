package dev.tg9.tg

import org.drinkless.tdlib.TdApi

/** The client-side cache TDLib expects every app to keep: TDLib announces each chat and user once
 *  (updateNewChat / updateUser) and from then on only sends deltas, so whatever isn't recorded
 *  here is simply gone. Follows the shape of TDLib's own Java example — the objects are kept and
 *  mutated in place as deltas arrive.
 *
 *  Main thread only (see [Tg]). */
object TgStore {
    val chats = HashMap<Long, TdApi.Chat>()
    val users = HashMap<Long, TdApi.User>()
    val files = HashMap<Int, TdApi.File>()

    /** Bumped on every change that can move or relabel a chat in the main list, so a screen can
     *  tell "something in the list changed" from a plain counter instead of diffing. */
    var chatListVersion: Long = 0L
        private set

    /** Same, for user names and statuses (a group's preview line names its sender). */
    var usersVersion: Long = 0L
        private set

    fun clear() {
        chats.clear()
        users.clear()
        files.clear()
        chatListVersion++
    }

    fun apply(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateNewChat -> {
                chats[update.chat.id] = update.chat
                chatListVersion++
            }
            is TdApi.UpdateChatTitle -> chat(update.chatId) { title = update.title }
            is TdApi.UpdateChatLastMessage -> chat(update.chatId) {
                lastMessage = update.lastMessage
                // An update with no positions doesn't clear them; it just means "order unchanged".
                update.positions.forEach { setPosition(this, it) }
            }
            is TdApi.UpdateChatPosition -> chat(update.chatId) { setPosition(this, update.position) }
            is TdApi.UpdateChatReadInbox -> chat(update.chatId) {
                lastReadInboxMessageId = update.lastReadInboxMessageId
                unreadCount = update.unreadCount
            }
            is TdApi.UpdateChatReadOutbox -> chat(update.chatId) {
                lastReadOutboxMessageId = update.lastReadOutboxMessageId
            }
            is TdApi.UpdateChatIsMarkedAsUnread -> chat(update.chatId) { isMarkedAsUnread = update.isMarkedAsUnread }
            is TdApi.UpdateChatNotificationSettings -> chat(update.chatId) { notificationSettings = update.notificationSettings }
            is TdApi.UpdateChatUnreadMentionCount -> chat(update.chatId) { unreadMentionCount = update.unreadMentionCount }
            is TdApi.UpdateChatDraftMessage -> chat(update.chatId) {
                draftMessage = update.draftMessage
                update.positions.forEach { setPosition(this, it) }
            }
            is TdApi.UpdateUser -> {
                users[update.user.id] = update.user
                usersVersion++
            }
            // Deliberately not a usersVersion bump: statuses change constantly and no list row
            // shows them.
            is TdApi.UpdateUserStatus -> users[update.userId]?.status = update.status
            is TdApi.UpdateFile -> files[update.file.id] = update.file
            else -> {}
        }
    }

    private inline fun chat(chatId: Long, change: TdApi.Chat.() -> Unit) {
        val chat = chats[chatId] ?: return
        chat.change()
        chatListVersion++
    }

    /** A position with order 0 means "removed from that list". */
    private fun setPosition(chat: TdApi.Chat, position: TdApi.ChatPosition) {
        val kept = chat.positions.filterNot { sameList(it.list, position.list) }
        chat.positions = if (position.order == 0L) kept.toTypedArray() else (kept + position).toTypedArray()
    }

    /** The main chat list, in TDLib's own order (pinned first, then by last activity). */
    fun mainChats(): List<TdApi.Chat> = chats.values
        .mapNotNull { chat -> mainPosition(chat)?.let { chat to it } }
        .sortedWith(compareByDescending<Pair<TdApi.Chat, TdApi.ChatPosition>> { it.second.order }.thenByDescending { it.first.id })
        .map { it.first }

    fun mainPosition(chat: TdApi.Chat): TdApi.ChatPosition? =
        chat.positions.firstOrNull { it.list is TdApi.ChatListMain && it.order != 0L }

    /** The most recent copy of a file: the one inside a message goes stale as soon as a download
     *  starts, and every later state of it arrives only as updateFile. */
    fun latest(file: TdApi.File): TdApi.File = files[file.id] ?: file

    fun userName(userId: Long): String {
        val user = users[userId] ?: return ""
        return listOf(user.firstName, user.lastName).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { user.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" } ?: "Deleted account" }
    }

    fun senderName(sender: TdApi.MessageSender): String = when (sender) {
        is TdApi.MessageSenderUser -> userName(sender.userId)
        is TdApi.MessageSenderChat -> chats[sender.chatId]?.title.orEmpty()
        else -> ""
    }

    fun isGroup(chat: TdApi.Chat): Boolean = when (val type = chat.type) {
        is TdApi.ChatTypeBasicGroup -> true
        is TdApi.ChatTypeSupergroup -> !type.isChannel
        else -> false
    }

    fun isChannel(chat: TdApi.Chat): Boolean = (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == true

    fun isMuted(chat: TdApi.Chat): Boolean = dev.tg9.chats.isMuted(chat.notificationSettings)

    private fun sameList(a: TdApi.ChatList, b: TdApi.ChatList): Boolean = when {
        a is TdApi.ChatListMain && b is TdApi.ChatListMain -> true
        a is TdApi.ChatListArchive && b is TdApi.ChatListArchive -> true
        a is TdApi.ChatListFolder && b is TdApi.ChatListFolder -> a.chatFolderId == b.chatFolderId
        else -> false
    }
}
