package dev.tg9

import org.drinkless.tdlib.TdApi

/** Minimal TdApi objects for tests: the generated classes have public no-arg constructors and
 *  public fields, so each test sets only what it reads. */
object Fixtures {
    fun text(body: String): TdApi.MessageText = TdApi.MessageText().apply { text = TdApi.FormattedText(body, emptyArray()) }

    fun caption(body: String) = TdApi.FormattedText(body, emptyArray())

    fun photo(captionText: String = "", vararg sides: Int): TdApi.MessagePhoto = TdApi.MessagePhoto().apply {
        caption = caption(captionText)
        photo = TdApi.Photo().apply {
            sizes = sides.map { side ->
                TdApi.PhotoSize().apply {
                    width = side
                    height = side * 3 / 4
                    photo = TdApi.File().apply { id = side }
                }
            }.toTypedArray()
        }
    }

    fun voice(seconds: Int): TdApi.MessageVoiceNote = TdApi.MessageVoiceNote().apply {
        caption = caption("")
        voiceNote = TdApi.VoiceNote().apply {
            duration = seconds
            voice = TdApi.File().apply { local = TdApi.LocalFile() }
        }
    }

    fun message(
        id: Long,
        content: TdApi.MessageContent,
        date: Int,
        outgoing: Boolean = false,
        fromUser: Long = 1L,
    ): TdApi.Message = TdApi.Message().apply {
        this.id = id
        this.content = content
        this.date = date
        this.isOutgoing = outgoing
        senderId = TdApi.MessageSenderUser(fromUser)
    }

    fun chat(
        id: Long,
        title: String,
        last: TdApi.Message? = null,
        unread: Int = 0,
        order: Long = id,
    ): TdApi.Chat = TdApi.Chat().apply {
        this.id = id
        this.title = title
        this.lastMessage = last
        this.unreadCount = unread
        type = TdApi.ChatTypePrivate(id)
        positions = arrayOf(TdApi.ChatPosition().apply { list = TdApi.ChatListMain(); this.order = order })
        notificationSettings = TdApi.ChatNotificationSettings().apply { useDefaultMuteFor = true }
    }
}
