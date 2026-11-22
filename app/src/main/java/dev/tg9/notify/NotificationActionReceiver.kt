package dev.tg9.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.tg9.tg.Tg
import org.drinkless.tdlib.TdApi

/** The message notification's "Mark as read": marks the notified messages viewed, the same thing
 *  opening the chat does, without opening it. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MARK_READ) return
        val groupId = intent.getIntExtra(EXTRA_GROUP_ID, 0)
        val chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0L)
        val messageIds = intent.getLongArrayExtra(EXTRA_MESSAGE_IDS) ?: LongArray(0)
        Tg.init(context)
        Notifications.dismissGroup(groupId)
        if (messageIds.isEmpty()) return
        // The tap can be what starts the process (Android killed it since the notification was
        // posted). TDLib then still has to open its database before it takes requests, so wait for
        // it — within the ~10 s a receiver gets — and keep the process alive until it has answered.
        val pending = goAsync()
        Tg.whenReady(READY_TIMEOUT_MS) { ready ->
            if (!ready) {
                pending.finish()
                return@whenReady
            }
            Tg.send(TdApi.ViewMessages(chatId, messageIds, TdApi.MessageSourceNotification(), true)) { result ->
                if (result is TdApi.Error) Log.w("TG9", "mark as read: ${result.code} ${result.message}")
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_MARK_READ = "dev.tg9.action.MARK_READ"
        private const val READY_TIMEOUT_MS = 8_000L
        private const val EXTRA_GROUP_ID = "group_id"
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_MESSAGE_IDS = "message_ids"

        fun markReadIntent(context: Context, groupId: Int, chatId: Long, messageIds: List<Long>): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                groupId,
                Intent(context, NotificationActionReceiver::class.java)
                    .setAction(ACTION_MARK_READ)
                    .putExtra(EXTRA_GROUP_ID, groupId)
                    .putExtra(EXTRA_CHAT_ID, chatId)
                    .putExtra(EXTRA_MESSAGE_IDS, messageIds.toLongArray()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
