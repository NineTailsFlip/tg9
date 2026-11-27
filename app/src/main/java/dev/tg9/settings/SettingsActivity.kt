package dev.tg9.settings

import android.content.Intent
import android.provider.Settings
import dev.tg9.Prefs
import dev.tg9.notify.ConnectionService
import dev.tg9.notify.Notifications
import dev.tg9.tg.Storage
import dev.tg9.tg.Tg
import dev.tg9.tg.TgStore
import io.github.ninetailsflip.update.updatesSection
import life.furrer.nine.core.NineSurface
import life.furrer.nine.core.settings.NineSettingsActivity
import life.furrer.nine.core.settings.SettingsScreen
import org.drinkless.tdlib.TdApi

private const val MIN_FONT_SIZE = 12
private const val MAX_FONT_SIZE = 32

/** TG9's Settings; Theme, Backup and About come from :core like in every Nine app. */
class SettingsActivity : NineSettingsActivity() {

    /** The cache's size, asked of TDLib each time the screen shows. */
    private var cacheLabel = ""
    private var clearing = false

    override fun onResume() {
        super.onResume()
        loadCacheSize()
    }

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
                off = "off (only while Telegram is open)",
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
        if (Tg.isReady) {
            screen.section("Storage") {
                action("Clear cache", value = { if (clearing) "clearing…" else cacheLabel }) { confirmClearCache() }
            }
        }
        updatesSection(screen, this, ::refresh)
    }

    private fun loadCacheSize() {
        if (!Tg.isReady) return
        Tg.request<TdApi.StorageStatisticsFast>(TdApi.GetStorageStatisticsFast()) { stats ->
            if (isDestroyed) return@request
            cacheLabel = Storage.label(stats)
            refresh()
        }
    }

    private fun confirmClearCache() {
        if (clearing) return
        confirm(
            "Delete downloaded photos, videos, voice messages and files? They download again when " +
                "opened; files saved to the phone stay. Files not opened for ${Storage.MAX_AGE_DAYS} days, " +
                "and the oldest beyond ${Storage.MAX_CACHE_MB} MB, go by themselves once a day.",
            yes = "Clear cache",
            no = "Keep",
        ) { clearCache() }
    }

    private fun clearCache() {
        clearing = true
        refresh()
        Tg.send(Storage.clearAll()) {
            clearing = false
            if (isDestroyed) return@send
            loadCacheSize()
            refresh()
        }
    }

    private fun accountLabel(): String {
        if (!Tg.isReady) return "not signed in"
        val me = TgStore.users[Tg.myUserId] ?: return "signed in"
        val name = TgStore.userName(me.id)
        return if (me.phoneNumber.isNotBlank()) "$name, +${me.phoneNumber}" else name
    }

    private fun confirmLogOut() {
        confirm("Log out of Telegram on this phone? Your chats stay in Telegram.", yes = "Log out") {
            Tg.logOut()
            finish()
        }
    }
}
