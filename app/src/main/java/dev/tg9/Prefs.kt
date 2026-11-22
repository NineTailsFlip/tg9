package dev.tg9

import android.content.Context
import life.furrer.nine.core.prefs.NinePrefs

/** TG9's own settings, in the same prefs file as core's (see [NinePrefs.of]) so Settings' Backup
 *  exports them together. */
object Prefs {
    private const val KEY_BACKGROUND_CONNECTION = "background_connection"
    private const val KEY_MESSAGE_FONT_SIZE = "message_font_size"
    private const val KEY_DRAFT_PREFIX = "draft_"

    const val DEFAULT_MESSAGE_FONT_SIZE = 16

    /** On by default: without it a keypad phone (no Google push) gets no messages while TG9
     *  isn't on screen, which is not what anyone installing a messenger expects. */
    fun backgroundConnection(context: Context): Boolean =
        NinePrefs.of(context).getBoolean(KEY_BACKGROUND_CONNECTION, true)

    fun setBackgroundConnection(context: Context, value: Boolean) {
        NinePrefs.of(context).edit().putBoolean(KEY_BACKGROUND_CONNECTION, value).apply()
    }

    fun messageFontSize(context: Context): Int =
        NinePrefs.of(context).getInt(KEY_MESSAGE_FONT_SIZE, DEFAULT_MESSAGE_FONT_SIZE)

    fun setMessageFontSize(context: Context, value: Int) {
        NinePrefs.of(context).edit().putInt(KEY_MESSAGE_FONT_SIZE, value).apply()
    }

    /** A reply typed and walked away from. Kept locally rather than as Telegram's own cloud
     *  draft: simpler, and nothing half-typed on a keypad leaves the phone unasked. */
    fun draft(context: Context, chatId: Long): String =
        NinePrefs.of(context).getString(KEY_DRAFT_PREFIX + chatId, "").orEmpty()

    fun setDraft(context: Context, chatId: Long, text: String) {
        val edit = NinePrefs.of(context).edit()
        if (text.isBlank()) edit.remove(KEY_DRAFT_PREFIX + chatId) else edit.putString(KEY_DRAFT_PREFIX + chatId, text)
        edit.apply()
    }
}
