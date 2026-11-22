package dev.tg9.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reconnects after a reboot or an app update, so messages keep arriving without opening TG9 —
 *  both broadcasts are among the few from which Android still allows a foreground-service start. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> ConnectionService.startIfEnabled(context)
        }
    }
}
