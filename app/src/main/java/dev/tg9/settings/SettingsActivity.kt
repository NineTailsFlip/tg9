package dev.tg9.settings

import android.app.AlertDialog
import android.content.Intent
import android.provider.Settings
import dev.tg9.Prefs
import dev.tg9.notify.ConnectionService
import dev.tg9.notify.Notifications
import dev.tg9.tg.Tg
import dev.tg9.tg.TgStore
import life.furrer.nine.core.NineSurface
import life.furrer.nine.core.settings.NineSettingsActivity
import life.furrer.nine.core.settings.SettingsScreen
import life.furrer.nine.core.ui.dialogThemeResId

private const val MIN_FONT_SIZE = 12
private const val MAX_FONT_SIZE = 32

/** TG9's Settings; Theme, Backup and About come from :core like in every Nine app. */
class SettingsActivity : NineSettingsActivity() {

    override fun buildAppSections(screen: SettingsScreen, surface: NineSurface) {
        screen.section("Telegram") {
            action("Account", value = ::accountLabel) {}
            toggle(
                label = "Background connection",
                get = { Prefs.backgroundConnection(this@SettingsActivity) },
                set = { on ->
                    Prefs.setBackgroundConnection(this@SettingsActivity, on)
                    if (on) ConnectionService.startIfEnabled(this@SettingsActivity) else ConnectionService.stop(this@SettingsActivity)
                },
                on = "on (messages arrive any time)",
                off = "off (only while TG9 is open)",
            )
            action("Notification sound and vibration") {
                startActivity(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.CHANNEL_MESSAGES),
                )
            }
            if (Tg.isReady) action("Log out") { confirmLogOut() }
        }
        screen.section("Chats") {
            intRange(
                label = "Text size",
                min = MIN_FONT_SIZE,
                max = MAX_FONT_SIZE,
                get = { Prefs.messageFontSize(this@SettingsActivity) },
                set = { Prefs.setMessageFontSize(this@SettingsActivity, it) },
            )
        }
    }

    private fun accountLabel(): String {
        if (!Tg.isReady) return "not signed in"
        val me = TgStore.users[Tg.myUserId] ?: return "signed in"
        val name = TgStore.userName(me.id)
        return if (me.phoneNumber.isNotBlank()) "$name, +${me.phoneNumber}" else name
    }

    private fun confirmLogOut() {
        AlertDialog.Builder(this, dialogThemeResId())
            .setMessage("Log out of Telegram on this phone? Your chats stay in Telegram.")
            .setPositiveButton("Log out") { _, _ ->
                Tg.logOut()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
