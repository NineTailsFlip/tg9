package dev.tg9.notify

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import dev.tg9.Prefs
import dev.tg9.R
import dev.tg9.chats.ChatListActivity
import dev.tg9.tg.Tg
import java.io.File
import org.drinkless.tdlib.TdApi

/** Keeps the process — and with it TDLib's connection — alive while no screen is open. A phone
 *  without Google Play Services gets no Telegram push, so this is the only way new messages (and
 *  their notifications) arrive in the background. It shows a minimum-importance notification,
 *  which Android requires of any foreground service; the channel can be hidden in system settings.
 *
 *  It does no work of its own: TDLib already runs in the process, and [Notifications] already
 *  listens to it. The service exists only so Android doesn't kill the process. */
class ConnectionService : Service() {

    private val listener = Tg.Listener { update ->
        when (update) {
            is TdApi.UpdateConnectionState -> refresh()
            is TdApi.UpdateAuthorizationState -> when (update.authorizationState) {
                is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateClosed, is TdApi.AuthorizationStateWaitPhoneNumber -> stopSelf()
                else -> {}
            }
            else -> {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        Tg.init(this)
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        Tg.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        Tg.removeListener(listener)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refresh() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val text = when (Tg.connectionState) {
            is TdApi.ConnectionStateReady -> "Connected"
            is TdApi.ConnectionStateWaitingForNetwork -> "Waiting for network"
            is TdApi.ConnectionStateUpdating -> "Updating"
            else -> "Connecting"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, ChatListActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, Notifications.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("TG9")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1

        /** Starts the service when the user wants it and there's an account to stay connected
         *  for (TDLib's database exists). Android refuses a foreground-service start from the
         *  background in some situations; then it simply starts with the next screen instead. */
        fun startIfEnabled(context: Context) {
            if (!Prefs.backgroundConnection(context)) return
            if (!File(context.filesDir, "tdlib").exists()) return
            try {
                context.startForegroundService(Intent(context, ConnectionService::class.java))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.i("TG9", "background connection not started now: ${e.message}")
            } catch (e: IllegalStateException) {
                Log.i("TG9", "background connection not started now: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }
    }
}
