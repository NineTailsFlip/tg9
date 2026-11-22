package dev.tg9.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.tg9.Prefs
import dev.tg9.tg.Content
import dev.tg9.tg.Tg
import dev.tg9.tg.TgStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.NineActivity
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.dp
import org.drinkless.tdlib.TdApi

/** A whole message, for the ones the chat screen cuts at six lines: who, when, then the text,
 *  scrolled with Up/Down. */
class MessageDetailActivity : NineActivity() {

    private lateinit var scroll: ScrollView
    private lateinit var body: TextView
    private lateinit var meta: TextView
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet
    private var text = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        meta = TextView(this).apply {
            textSize = 12f
            setTextColor(Palette.GRAY_MUTED)
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(10), dp(Dimens.SIDE_PADDING_DP), dp(8))
        }
        body = TextView(this).apply {
            textSize = Prefs.messageFontSize(this@MessageDetailActivity).toFloat()
            setTextColor(Palette.WHITE)
            setTextIsSelectable(false)
            setPadding(dp(Dimens.SIDE_PADDING_DP), 0, dp(Dimens.SIDE_PADDING_DP), dp(16))
        }
        scroll = ScrollView(this).apply {
            isFocusable = false
            addView(LinearLayout(this@MessageDetailActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(meta)
                addView(body)
            })
        }
        hintBar = HintBar(this)
        menuSheet = MenuSheet(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hintBar)
        }
        setContentView(
            FrameLayout(this).apply {
                addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
        )
        hintBar.setText("↑↓ scroll · * menu · ‹ back")

        val chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0L)
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0L)
        Tg.request<TdApi.Message>(TdApi.GetMessage(chatId, messageId), onError = { body.text = it.message }) { show(it) }
    }

    private fun show(message: TdApi.Message) {
        text = Content.text(message.content)
        body.text = Content.body(message.content)
        val who = if (message.isOutgoing) "You" else TgStore.senderName(message.senderId)
        val date = Instant.ofEpochSecond(message.date.toLong()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault()))
        val forwarded = message.forwardInfo?.let { info ->
            when (val origin = info.origin) {
                is TdApi.MessageOriginUser -> "\nforwarded from ${TgStore.userName(origin.senderUserId)}"
                is TdApi.MessageOriginChat -> "\nforwarded from ${TgStore.chats[origin.senderChatId]?.title.orEmpty()}"
                is TdApi.MessageOriginChannel -> "\nforwarded from ${TgStore.chats[origin.chatId]?.title.orEmpty()}"
                is TdApi.MessageOriginHiddenUser -> "\nforwarded from ${origin.senderName}"
                else -> ""
            }
        }.orEmpty()
        meta.text = "$who · $date$forwarded"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuSheet.handleKeyDown(keyCode, event)) return true
        val step = scroll.height * 2 / 3
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> scroll.smoothScrollBy(0, -step)
            KeyEvent.KEYCODE_DPAD_DOWN -> scroll.smoothScrollBy(0, step)
            KeyEvent.KEYCODE_STAR -> menuSheet.show(
                listOfNotNull(
                    MenuItem("Copy text") { copy() }.takeIf { text.isNotEmpty() },
                    MenuItem("Back") { finish() },
                ),
            )
            KeyEvent.KEYCODE_BACK -> finish()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun copy() {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Message", text))
        hintBar.setText("copied")
    }

    companion object {
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_MESSAGE_ID = "message_id"

        fun intentFor(context: Context, chatId: Long, messageId: Long): Intent =
            Intent(context, MessageDetailActivity::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_MESSAGE_ID, messageId)
    }
}
