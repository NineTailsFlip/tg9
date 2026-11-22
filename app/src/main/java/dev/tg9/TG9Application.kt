package dev.tg9

import android.app.Application
import dev.tg9.notify.ConnectionService
import dev.tg9.notify.Notifications
import dev.tg9.settings.SettingsActivity
import dev.tg9.tg.Tg
import io.github.ninetailsflip.update.Updates
import life.furrer.nine.core.KeyHelp
import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface

class TG9Application : Application() {
    override fun onCreate() {
        super.onCreate()
        Nine.init(tg9App)
        Updates.init(this, "NineTailsFlip/tg9")
        Notifications.init(this)
        Tg.init(this)
        ConnectionService.startIfEnabled(this)
    }
}

private val tg9App = NineApp(
    id = "tg9",
    name = "TG9",
    tagline = "Telegram on 9 keys",
    settingsActivity = SettingsActivity::class.java,
    websiteUrl = "https://github.com/NineTailsFlip/tg9",
    sourceUrl = "https://github.com/NineTailsFlip/tg9",
    trackerUrl = "https://github.com/NineTailsFlip/tg9/issues",
    surfaces = listOf(
        NineSurface(
            id = "",
            name = "TG9",
            introText = """
                TG9 ("Telegram on 9 keys") is an unofficial Telegram client for a keypad phone
                with no touchscreen, in the look and with the keys of the Nine apps: one column of
                plain text on black, a numbered "*" menu, and the phone's own T9 input for typing.

                It is built on TDLib, Telegram's own client library, and talks only to Telegram's
                servers. It is not made by Telegram.

                The chat list shows your chats in Telegram's order. Type to filter it by name.
                Open a chat to read it from the newest message up; Down past the last message
                opens the reply row. Photos, videos, GIFs and stickers open full screen; voice
                messages and music play in place; files, links, shared contacts and places have
                their own items in the "*" menu, which also records a voice message of your own and
                opens TG9's gallery to send photos and videos, one at a time or as an album.

                Without Google push, new messages only arrive while TG9 is connected. With
                "Background connection" on (Settings), TG9 keeps a quiet notification up and stays
                connected, so messages and their notifications arrive as they would in Telegram.
            """.trimIndent(),
            keyMap = listOf(
                KeyHelp("— Chat list —", ""),
                KeyHelp("any key", "Type to filter chats by name."),
                KeyHelp("Up / Down", "Move the selection."),
                KeyHelp("OK", "Open the selected chat."),
                KeyHelp("#", "Toggle showing unread chats only."),
                KeyHelp("*", "Open the menu: mark read/unread, settings, log out."),
                KeyHelp("Hold *", "Open Settings."),
                KeyHelp("Back", "Clear the filter; on an empty filter, close."),
                KeyHelp("— Chat —", ""),
                KeyHelp("Up / Down", "Move between messages; Up at the top loads older ones."),
                KeyHelp("Down, past the last message", "Open the reply row."),
                KeyHelp("Left / Right", "Jump to the previous/next day."),
                KeyHelp("OK", "Photo, video, GIF, sticker: open. Voice, music: play/pause. Text: read in full."),
                KeyHelp("OK or call key, in the reply row", "Send."),
                KeyHelp("Call key", "Open the reply row."),
                KeyHelp("#", "Play or pause the selected voice message."),
                KeyHelp("*", "Open the menu: reply, copy, open link, call, map, save file, delete, record voice."),
                KeyHelp("Back", "Close the chat; in the reply row, delete a character or leave."),
                KeyHelp("— Photo / video —", ""),
                KeyHelp("Up / Down", "Previous/next photo or video in the chat."),
                KeyHelp("OK", "Photo: zoom in/out. Video: pause/play. Big file: start the download."),
                KeyHelp("Left / Right", "Video: back/forward 10 s. Zoomed photo: pan (Up/Down too)."),
                KeyHelp("*", "Open the menu: save to phone, previous/next."),
                KeyHelp("— Gallery (* → Send photo or video) —", ""),
                KeyHelp("Up / Down", "Move; newest at the top."),
                KeyHelp("Left / Right", "Jump to the previous/next day."),
                KeyHelp("OK", "View full screen; there, OK sends."),
                KeyHelp("#", "Mark for an album (up to 10)."),
                KeyHelp("Call key", "Send the marked ones, or the selected one."),
                KeyHelp("— Voice recording —", ""),
                KeyHelp("OK", "Stop and send."),
                KeyHelp("Back", "Discard."),
            ),
        ),
    ),
)
