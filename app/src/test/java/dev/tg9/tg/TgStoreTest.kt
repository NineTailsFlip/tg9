package dev.tg9.tg

import dev.tg9.Fixtures
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TgStoreTest {
    @Before
    fun reset() = TgStore.clear()

    private fun position(order: Long, pinned: Boolean = false) = TdApi.ChatPosition().apply {
        list = TdApi.ChatListMain()
        this.order = order
        isPinned = pinned
    }

    private fun add(vararg chats: TdApi.Chat) = chats.forEach { TgStore.apply(TdApi.UpdateNewChat(it)) }

    @Test
    fun mainChatsFollowTdlibOrder() {
        add(Fixtures.chat(1, "a", order = 10), Fixtures.chat(2, "b", order = 30), Fixtures.chat(3, "c", order = 20))
        assertEquals(listOf(2L, 3L, 1L), TgStore.mainChats().map { it.id })

        TgStore.apply(TdApi.UpdateChatPosition(1, position(40)))
        assertEquals(listOf(1L, 2L, 3L), TgStore.mainChats().map { it.id })
    }

    @Test
    fun orderZeroRemovesFromTheList() {
        add(Fixtures.chat(1, "a", order = 10), Fixtures.chat(2, "b", order = 20))
        TgStore.apply(TdApi.UpdateChatPosition(2, position(0)))
        assertEquals(listOf(1L), TgStore.mainChats().map { it.id })
        assertNull(TgStore.mainPosition(TgStore.chats.getValue(2)))
    }

    @Test
    fun archivePositionsDontTouchTheMainList() {
        add(Fixtures.chat(1, "a", order = 10))
        TgStore.apply(
            TdApi.UpdateChatPosition(1, TdApi.ChatPosition().apply { list = TdApi.ChatListArchive(); order = 99 }),
        )
        assertEquals(10L, TgStore.mainPosition(TgStore.chats.getValue(1))!!.order)
        assertEquals(2, TgStore.chats.getValue(1).positions.size)
    }

    @Test
    fun lastMessageAndReadState() {
        add(Fixtures.chat(1, "a", unread = 3))
        val before = TgStore.chatListVersion
        val message = Fixtures.message(7, Fixtures.text("hi"), 100)
        TgStore.apply(TdApi.UpdateChatLastMessage(1, message, arrayOf(position(50))))
        TgStore.apply(TdApi.UpdateChatReadInbox(1, 7, 0))
        val chat = TgStore.chats.getValue(1)
        assertEquals(7L, chat.lastMessage!!.id)
        assertEquals(50L, TgStore.mainPosition(chat)!!.order)
        assertEquals(0, chat.unreadCount)
        assertTrue(TgStore.chatListVersion > before)
    }

    @Test
    fun deltasForUnknownChatsAreIgnored() {
        val before = TgStore.chatListVersion
        TgStore.apply(TdApi.UpdateChatTitle(42, "nobody"))
        assertEquals(before, TgStore.chatListVersion)
        assertTrue(TgStore.chats.isEmpty())
    }

    @Test
    fun userNamesFallBack() {
        TgStore.apply(TdApi.UpdateUser(user(1, "Anna", "Smith")))
        TgStore.apply(TdApi.UpdateUser(user(2, "", "", username = "bob")))
        TgStore.apply(TdApi.UpdateUser(user(3, "", "")))
        assertEquals("Anna Smith", TgStore.userName(1))
        assertEquals("@bob", TgStore.userName(2))
        assertEquals("Deleted account", TgStore.userName(3))
        assertEquals("", TgStore.userName(99))
        assertEquals("Anna Smith", TgStore.senderName(TdApi.MessageSenderUser(1)))
    }

    @Test
    fun statusUpdatesDontBumpTheUsersVersion() {
        TgStore.apply(TdApi.UpdateUser(user(1, "Anna", "")))
        val before = TgStore.usersVersion
        TgStore.apply(TdApi.UpdateUserStatus(1, TdApi.UserStatusOnline(0)))
        assertEquals(before, TgStore.usersVersion)
        assertTrue(TgStore.users.getValue(1).status is TdApi.UserStatusOnline)
    }

    @Test
    fun muteFollowsTheChatsOwnSettingOnly() {
        add(Fixtures.chat(1, "a"))
        val chat = TgStore.chats.getValue(1)
        assertFalse(TgStore.isMuted(chat))
        TgStore.apply(
            TdApi.UpdateChatNotificationSettings(
                1,
                TdApi.ChatNotificationSettings().apply { useDefaultMuteFor = false; muteFor = Int.MAX_VALUE },
            ),
        )
        assertTrue(TgStore.isMuted(chat))
    }

    @Test
    fun latestFileWins() {
        val stale = TdApi.File().apply { id = 5; local = TdApi.LocalFile().apply { isDownloadingCompleted = false } }
        val fresh = TdApi.File().apply { id = 5; local = TdApi.LocalFile().apply { isDownloadingCompleted = true } }
        TgStore.apply(TdApi.UpdateFile(fresh))
        assertTrue(TgStore.latest(stale).local.isDownloadingCompleted)
    }

    private fun user(id: Long, first: String, last: String, username: String? = null) = TdApi.User().apply {
        this.id = id
        firstName = first
        lastName = last
        usernames = username?.let { TdApi.Usernames().apply { activeUsernames = arrayOf(it) } }
    }
}
