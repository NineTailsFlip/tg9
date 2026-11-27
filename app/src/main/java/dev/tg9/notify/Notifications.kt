package dev.tg9.notify

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.tg9.R
import dev.tg9.chat.ChatActivity
import dev.tg9.chats.ChatListActivity
import dev.tg9.tg.Content
import dev.tg9.tg.Tg
import dev.tg9.tg.TgStore
import life.furrer.nine.core.prefs.NinePrefs
import org.drinkless.tdlib.TdApi

/** New-message notifications, driven entirely by TDLib's notification API: TDLib decides what
 *  deserves a notification (it already applies each chat's mute settings, mentions, and "seen on
 *  another device"), and sends updateNotificationGroup as notifications appear and disappear. This
 *  class only mirrors those groups into Android notifications, one per chat.
 *
 *  Main thread only, like everything that hangs off [Tg]. */
object Notifications {
    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_SILENT = "messages_silent"
    const val CHANNEL_CONNECTION = "connection"

    private const val KEY_ASKED_PERMISSION = "asked_notification_permission"
    private const val REQUEST_POST_NOTIFICATIONS = 400
    private const val MAX_LINES = 6

    private lateinit var appContext: Context

    private class Group(val chatId: Long, val notifications: MutableList<TdApi.Notification>)

    private val groups = HashMap<Int, Group>()

    fun init(context: Context) {
        appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New Telegram messages"
                enableVibration(true)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SILENT, "Silent messages", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Messages their sender sent without sound"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "The quiet notification shown while Telegram stays connected in the background"
                setShowBadge(false)
            },
        )
        Tg.addGlobalListener { update ->
            when (update) {
                is TdApi.UpdateActiveNotifications -> {
                    groups.clear()
                    update.groups.forEach { g -> groups[g.id] = Group(g.chatId, g.notifications.toMutableList()) }
                    groups.keys.forEach(::render)
                }
                is TdApi.UpdateNotificationGroup -> {
                    val group = groups.getOrPut(update.notificationGroupId) { Group(update.chatId, mutableListOf()) }
                    group.notifications.removeAll { it.id in update.removedNotificationIds }
                    group.notifications.addAll(update.addedNotifications)
                    render(update.notificationGroupId)
                }
                is TdApi.UpdateAuthorizationState -> if (update.authorizationState is TdApi.AuthorizationStateLoggingOut) {
                    groups.clear()
                    manager.cancelAll()
                }
                else -> {}
            }
        }
    }

    /** Asked once, from the chat list, the first time it's shown after sign-in. */
    fun ensurePermission(activity: Activity) {
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = NinePrefs.of(activity)
        if (prefs.getBoolean(KEY_ASKED_PERMISSION, false) || !Tg.isReady) return
        prefs.edit().putBoolean(KEY_ASKED_PERMISSION, true).apply()
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
    }

    /** Opening a chat clears its notification here and tells TDLib it's been dealt with. */
    fun cancelChat(context: Context, chatId: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        groups.filterValues { it.chatId == chatId }.forEach { (id, group) ->
            manager.cancel(id)
            group.notifications.maxOfOrNull { it.id }?.let { Tg.send(TdApi.RemoveNotificationGroup(id, it)) }
            group.notifications.clear()
        }
    }

    private fun render(groupId: Int) {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        val group = groups[groupId] ?: return
        val shown = group.notifications.sortedBy { it.id }
        if (shown.isEmpty()) {
            manager.cancel(groupId)
            groups.remove(groupId)
            return
        }
        val chat = TgStore.chats[group.chatId]
        val title = chat?.title ?: "Telegram"
        val showSenders = chat != null && TgStore.isGroup(chat)
        val lines = shown.takeLast(MAX_LINES).map { line(it, showSenders) }
        val silent = shown.all { it.isSilent }

        val open = TaskStackBuilder.create(appContext)
            .addNextIntent(Intent(appContext, ChatListActivity::class.java))
            .addNextIntent(ChatActivity.intentFor(appContext, group.chatId))
            .getPendingIntent(groupId, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // Silent notifications (sent with "send without sound") go to a channel that doesn't
        // alert: the platform Notification.Builder has no per-notification "silent" switch.
        val builder = Notification.Builder(appContext, if (silent) CHANNEL_SILENT else CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (shown.size > 1) "$title (${shown.size})" else title)
            .setContentText(lines.last())
            .setWhen(shown.last().date * 1000L)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setStyle(Notification.InboxStyle().also { style -> lines.forEach { style.addLine(it) } })
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Mark as read",
                    NotificationActionReceiver.markReadIntent(appContext, groupId, group.chatId, shown.mapNotNull { messageId(it) }),
                ).build(),
            )
        manager.notify(groupId, builder.build())
    }

    private fun messageId(notification: TdApi.Notification): Long? =
        (notification.type as? TdApi.NotificationTypeNewMessage)?.message?.id

    private fun line(notification: TdApi.Notification, showSenders: Boolean): String {
        val type = notification.type as? TdApi.NotificationTypeNewMessage ?: return "New notification"
        if (!type.showPreview) return "New message"
        val message = type.message
        val text = Content.summary(message.content).ifEmpty { "Message" }
        if (!showSenders) return text
        val who = TgStore.senderName(message.senderId).substringBefore(' ')
        return if (who.isBlank()) text else "$who: $text"
    }

    /** Called by [NotificationActionReceiver] after "Mark as read". */
    internal fun dismissGroup(groupId: Int) {
        appContext.getSystemService(NotificationManager::class.java).cancel(groupId)
        groups.remove(groupId)
    }
}
