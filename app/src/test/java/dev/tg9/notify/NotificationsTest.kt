package dev.tg9.notify

import android.app.Notification
import android.app.NotificationManager
import dev.tg9.Fixtures
import dev.tg9.tg.TgStore
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A plain Application: TG9Application starts the native TDLib client. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class NotificationsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val chat = 7L

    @Before
    fun setUp() {
        Notifications.init(context)
        // Forget the groups an earlier test left: TDLib's "these are all there are".
        Notifications.onUpdate(TdApi.UpdateActiveNotifications(emptyArray()))
        manager.cancelAll()
        TgStore.clear()
        TgStore.apply(TdApi.UpdateNewChat(Fixtures.chat(chat, "Mom")))
    }

    private fun notification(id: Int, text: String, silent: Boolean = false) =
        TdApi.Notification(id, 100 + id, silent, TdApi.NotificationTypeNewMessage(Fixtures.message(id.toLong(), Fixtures.text(text), 100 + id, chatId = chat), true))

    private fun group(added: Array<TdApi.Notification>, removed: IntArray = IntArray(0), groupId: Int = 1) =
        TdApi.UpdateNotificationGroup().apply {
            notificationGroupId = groupId
            chatId = chat
            addedNotifications = added
            removedNotificationIds = removed
        }

    private fun shown(groupId: Int = 1): Notification? = shadowOf(manager).getNotification(groupId)

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Test
    fun oneChatIsOneNotificationCountingItsMessages() {
        Notifications.onUpdate(group(arrayOf(notification(1, "hi"))))
        assertEquals("Mom", shown()!!.title())

        Notifications.onUpdate(group(arrayOf(notification(2, "are you coming?"))))
        val n = shown()!!
        assertEquals("Mom (2)", n.title())
        assertEquals("are you coming?", n.text())
        assertEquals(Notifications.CHANNEL_MESSAGES, n.channelId)
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun messagesSentWithoutSoundDontAlert() {
        Notifications.onUpdate(group(arrayOf(notification(1, "shh", silent = true))))
        assertEquals(Notifications.CHANNEL_SILENT, shown()!!.channelId)
        // One message with sound makes the whole group alert.
        Notifications.onUpdate(group(arrayOf(notification(2, "hey"))))
        assertEquals(Notifications.CHANNEL_MESSAGES, shown()!!.channelId)
    }

    @Test
    fun removingTheLastMessageCancelsIt() {
        Notifications.onUpdate(group(arrayOf(notification(1, "a"), notification(2, "b"))))
        Notifications.onUpdate(group(emptyArray(), intArrayOf(1)))
        assertEquals("Mom", shown()!!.title())
        Notifications.onUpdate(group(emptyArray(), intArrayOf(2)))
        assertNull(shown())
    }

    @Test
    fun anEmptyActiveGroupIsDroppedWithoutBreakingTheOthers() {
        // Rendering dropped the empty group from the map it was walking: a
        // ConcurrentModificationException before.
        Notifications.onUpdate(
            TdApi.UpdateActiveNotifications(
                arrayOf(
                    TdApi.NotificationGroup(1, TdApi.NotificationGroupTypeMessages(), chat, 0, emptyArray()),
                    TdApi.NotificationGroup(2, TdApi.NotificationGroupTypeMessages(), chat, 1, arrayOf(notification(5, "still here"))),
                ),
            ),
        )
        assertNull(shown(1))
        assertEquals("still here", shown(2)!!.text())
    }

    @Test
    fun loggingOutClearsEverything() {
        Notifications.onUpdate(group(arrayOf(notification(1, "a"))))
        Notifications.onUpdate(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateLoggingOut()))
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }
}
