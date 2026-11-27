package dev.tg9.chat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.tg9.Prefs
import dev.tg9.gallery.GalleryActivity
import dev.tg9.media.MediaActivity
import dev.tg9.media.RecordVoiceActivity
import dev.tg9.media.SaveToPhone
import dev.tg9.media.VoicePlayer
import dev.tg9.notify.Notifications
import dev.tg9.tg.Content
import dev.tg9.tg.Media
import dev.tg9.tg.Tg
import dev.tg9.tg.TgStore
import dev.tg9.ui.SUBMIT_KEYS
import dev.tg9.ui.consumeAndActOnUp
import java.lang.ref.WeakReference
import java.time.LocalDate
import life.furrer.nine.core.settings.openNineSettings
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.LongPressKey
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.NineActivity
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.RowBinder
import life.furrer.nine.core.ui.RowListView
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.ui.relaunch
import life.furrer.nine.core.util.Async
import org.drinkless.tdlib.TdApi

private const val HISTORY_PAGE = 50
/** How many messages a freshly opened chat tries to have before it stops paging back: enough
 *  to fill a few screens, few enough to open instantly from TDLib's local database. */
private const val INITIAL_MESSAGES = 30
/** Below the viewer's 32: a file being saved in the background shouldn't hold up a photo being
 *  looked at. */
private const val SAVE_DOWNLOAD_PRIORITY = 16

/** One chat, oldest message at the top, opening on the newest — M9's ConversationActivity, with
 *  Telegram's extras (photos, voice messages, replies, groups) folded into the same one-column
 *  layout. No SearchField here, so keys are handled in the simpler onKeyDown shape, and the reply
 *  row is intercepted in dispatchKeyEvent exactly as M9 does it (see the comments there for the
 *  on-device findings). */
class ChatActivity : NineActivity() {

    private var chatId = 0L
    private lateinit var messages: ChatTimeline
    private var rows: List<MessageRow> = emptyList()
    private var reachedStart = false
    private var loadingHistory = false
    private var resumed = false
    private var replyToId = 0L

    private val starKey = LongPressKey(::openMenu) { openNineSettings() }

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var rowListView: RowListView
    private lateinit var replyBanner: TextView
    private lateinit var composeField: ComposeField
    private lateinit var keyFocusAnchor: View
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet

    /** Read once per screen; a change in Settings recreates the screen in onResume. */
    private var fontSize = Prefs.DEFAULT_MESSAGE_FONT_SIZE

    /** Updates that arrive together (TDLib sends them in bursts) rebuild the rows once, in a
     *  posted pass; [selectNewestAfterRebuild] carries "the user was at the newest message" over to
     *  it, judged against the rows as they were before the burst. */
    private var rebuildPosted = false
    private var selectNewestAfterRebuild = false
    private val postedRebuild = Runnable {
        rebuildPosted = false
        rebuildRows()
        if (selectNewestAfterRebuild) rowListView.selectLastSelectable()
        selectNewestAfterRebuild = false
    }

    /** Each row's height, measured once per rebuild. RowListView asks for every row's height on
     *  every key press, and measuring message text each time (a StaticLayout per message, hundreds
     *  per press in a long chat) was the input lag. */
    private var rowHeights = IntArray(0)

    private val binder = object : RowBinder {
        override fun rowCount(): Int = rows.size
        override fun createRowView(context: Context): View = MessageRowView(context, fontSize)
        override fun bind(view: View, index: Int, selected: Boolean) {
            val row = rows[index]
            val playing = (row as? MessageRow.Entry)?.let { entry ->
                if (VoicePlayer.isCurrent(chatId, entry.id)) VoicePlayer.progressText(voiceDuration(entry.id)) else null
            }
            (view as MessageRowView).bind(row, selected, playing)
        }
        override fun rowHeight(index: Int): Int = rowHeights[index]
        override fun isSelectable(index: Int): Boolean = rows[index].isSelectable()
    }

    private val listener = Tg.Listener { update -> onUpdate(update) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0L)
        messages = ChatTimeline(chatId)
        fontSize = Prefs.messageFontSize(this)

        title = TextView(this).apply {
            textSize = 18f
            maxLines = 1
            setTextColor(Palette.WHITE)
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(10), dp(Dimens.SIDE_PADDING_DP), 0)
        }
        subtitle = TextView(this).apply {
            textSize = 12f
            maxLines = 1
            setTextColor(Palette.GRAY_MUTED)
            setPadding(dp(Dimens.SIDE_PADDING_DP), 0, dp(Dimens.SIDE_PADDING_DP), dp(6))
        }
        rowListView = RowListView(this).apply { setBinder(binder) }
        keyFocusAnchor = View(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        replyBanner = TextView(this).apply {
            textSize = 11f
            maxLines = 1
            setTextColor(Palette.GRAY_MUTED)
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(4), dp(Dimens.SIDE_PADDING_DP), 0)
            visibility = View.GONE
        }
        composeField = ComposeField(this, "Message…", fontSize, ::updateHint)
        // OK and the call key send. Taken ahead of the T9 IME, which otherwise turns OK into a
        // space (confirmed on a user's phone), and acted on at the key's UP so the call key's UP
        // never reaches the system's fallback handler, which opens the dialer.
        composeField.view.onPreImeKey = { event -> consumeAndActOnUp(event, SUBMIT_KEYS) { sendComposed() } }
        hintBar = HintBar(this)
        menuSheet = MenuSheet(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(keyFocusAnchor, LinearLayout.LayoutParams(0, 0))
            addView(title)
            addView(subtitle)
            addView(rowListView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(replyBanner)
            addView(
                composeField.view,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(12)
                },
            )
            addView(hintBar)
        }
        setContentView(
            FrameLayout(this).apply {
                addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
        )
        composeField.view.setText(Prefs.draft(this, chatId))
        renderHeader()
        renderReplyBanner()
        updateHint()
        // Subscribed for the screen's whole life, not just while resumed: a voice message sent
        // from RecordVoiceActivity, or anything that arrives while a photo or Settings is on top,
        // lands while this screen is paused, and onResume only reloads an empty history. Marking
        // messages viewed is still gated on [resumed].
        Tg.addListener(ChatTimeline.UPDATES, listener)
        current = WeakReference(this)
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.messageFontSize(this) != fontSize) {
            relaunch(intentFor(this, chatId))
            return
        }
        resumed = true
        Tg.send(TdApi.OpenChat(chatId))
        VoicePlayer.onChange = { rowListView.notifyDataChanged() }
        Notifications.cancelChat(this, chatId)
        if (messages.isEmpty) loadHistory(initial = true) else markViewed()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        Tg.send(TdApi.CloseChat(chatId))
        VoicePlayer.onChange = {}
        starKey.reset()
        Prefs.setDraft(this, chatId, composeField.text.toString())
    }

    override fun onDestroy() {
        super.onDestroy()
        Tg.removeListener(listener)
        rowListView.removeCallbacks(postedRebuild)
        if (current?.get() === this) current = null
        if (isFinishing && VoicePlayer.chatId == chatId) VoicePlayer.stop()
    }

    private fun chat(): TdApi.Chat? = TgStore.chats[chatId]

    private fun renderHeader() {
        val chat = chat()
        title.text = chat?.title ?: ""
        subtitle.text = when {
            chat == null -> ""
            TgStore.isChannel(chat) -> "channel"
            TgStore.isGroup(chat) -> "group"
            else -> (chat.type as? TdApi.ChatTypePrivate)?.let { userStatus(it.userId) }.orEmpty()
        }
        subtitle.visibility = if (subtitle.text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun userStatus(userId: Long): String {
        val user = TgStore.users[userId] ?: return ""
        if (user.type is TdApi.UserTypeBot) return "bot"
        return when (user.status) {
            is TdApi.UserStatusOnline -> "online"
            is TdApi.UserStatusRecently -> "last seen recently"
            is TdApi.UserStatusLastWeek -> "last seen within a week"
            is TdApi.UserStatusLastMonth -> "last seen within a month"
            is TdApi.UserStatusOffline -> "offline"
            else -> ""
        }
    }

    // ── History ─────────────────────────────────────────────────────────────────────────────

    /** getChatHistory may return fewer messages than asked for (TDLib answers from its local
     *  database first), so the first load keeps paging back until it has [INITIAL_MESSAGES] or
     *  reaches the start of the chat. */
    private fun loadHistory(initial: Boolean) {
        if (loadingHistory || reachedStart) return
        loadingHistory = true
        val from = messages.oldestId()
        Tg.request<TdApi.Messages>(
            TdApi.GetChatHistory(chatId, from, 0, HISTORY_PAGE, false),
            onError = { loadingHistory = false },
        ) { result ->
            loadingHistory = false
            val anchor = selectedEntry()?.id
            if (messages.addHistory(result.messages) == 0) reachedStart = true
            rebuildRows()
            when {
                initial && rows.isNotEmpty() && anchor == null -> rowListView.selectLastSelectable()
                anchor != null -> selectMessage(anchor)
            }
            if (initial && messages.size < INITIAL_MESSAGES && !reachedStart) {
                loadHistory(initial = true)
            }
            markViewed()
        }
    }

    private fun rebuildRows() {
        val chat = chat()
        rows = buildMessageRows(
            messages.all(),
            ChatContext(
                showSenders = chat != null && TgStore.isGroup(chat),
                lastReadOutboxId = chat?.lastReadOutboxMessageId ?: 0L,
                senderName = TgStore::senderName,
                findMessage = messages::get,
            ),
            LocalDate.now(),
        )
        rowHeights = IntArray(rows.size) { messageRowHeightDp(this, rows[it], fontSize) }
        rowListView.notifyDataChanged()
        updateHint()
    }

    private fun selectMessage(id: Long) {
        val index = rows.indexOfFirst { it is MessageRow.Entry && it.id == id }
        if (index >= 0) rowListView.setSelectedIndex(index)
    }

    /** Everything loaded counts as seen while the chat is on screen — the same rule M9 applies
     *  ("opening a thread is what read means"). Each message is reported once. */
    private fun markViewed() {
        if (!resumed) return
        val ids = messages.takeUnviewed()
        if (ids.isNotEmpty()) Tg.send(TdApi.ViewMessages(chatId, ids, TdApi.MessageSourceChatHistory(), true))
    }

    private fun onUpdate(update: TdApi.Update) {
        val atNewest = rowListView.selectedIndex >= rows.size - 1
        when (messages.apply(update)) {
            ChatTimeline.Change.NONE -> {}
            ChatTimeline.Change.ROWS -> scheduleRebuild(selectNewest = false)
            ChatTimeline.Change.NEW_INCOMING -> {
                scheduleRebuild(selectNewest = atNewest)
                markViewed()
            }
            ChatTimeline.Change.NEW_OUTGOING -> {
                scheduleRebuild(selectNewest = true)
                markViewed()
            }
            ChatTimeline.Change.HEADER -> renderHeader()
        }
    }

    private fun scheduleRebuild(selectNewest: Boolean) {
        selectNewestAfterRebuild = selectNewestAfterRebuild || selectNewest
        if (rebuildPosted) return
        rebuildPosted = true
        rowListView.post(postedRebuild)
    }

    // ── Selection helpers ───────────────────────────────────────────────────────────────────

    private fun selectedEntry(): MessageRow.Entry? = rows.getOrNull(rowListView.selectedIndex) as? MessageRow.Entry

    private fun selectedMessage(): TdApi.Message? = selectedEntry()?.let { messages[it.id] }

    private fun voiceDuration(messageId: Long): Int =
        Content.playable(messages[messageId]?.content)?.durationSeconds ?: 0

    // ── Hint and compose ────────────────────────────────────────────────────────────────────

    private fun updateHint() {
        if (composeField.isComposing) {
            hintBar.setText(if (composeField.text.isEmpty()) "ok send · ‹ back to messages" else "ok or call key send · ‹ delete")
            return
        }
        hintBar.setText(
            when (selectedEntry()?.kind) {
                MessageKind.PHOTO -> "* menu · ok open · ↓ reply"
                MessageKind.VIDEO -> "* menu · ok play video · ↓ reply"
                MessageKind.VOICE -> "* menu · ok play · ↓ reply"
                MessageKind.FILE -> "* menu (save) · ok details · ↓ reply"
                null -> "* menu · ↓ write"
                else -> "* menu · ok read · ↓ reply"
            },
        )
    }

    private fun renderReplyBanner() {
        val original = messages[replyToId] ?: TgStore.chats[chatId]?.lastMessage?.takeIf { it.id == replyToId }
        if (replyToId == 0L) {
            replyBanner.visibility = View.GONE
            return
        }
        val who = original?.let { if (it.isOutgoing) "You" else TgStore.senderName(it.senderId) }.orEmpty()
        val text = original?.let { Content.summary(it.content) }.orEmpty()
        replyBanner.text = "↪ Replying to $who: $text"
        replyBanner.visibility = View.VISIBLE
    }

    private fun startComposing() {
        if (!composeField.start()) return
        updateHint()
    }

    /** Leaving the reply row needs a fresh window, not just dropped focus, because of the
     *  device IME capturing `*`/`#` once a field has had a live connection — see core's
     *  [relaunch] and M9's ConversationActivity, where this was confirmed on hardware. */
    private fun stopComposing() {
        if (!composeField.stop()) return
        keyFocusAnchor.requestFocus()
        relaunch(intentFor(this, chatId))
    }

    private fun sendComposed() {
        val body = composeField.text.toString().trim()
        if (body.isEmpty()) return
        val send = TdApi.SendMessage().apply {
            chatId = this@ChatActivity.chatId
            if (replyToId != 0L) replyTo = TdApi.InputMessageReplyToMessage().apply { messageId = replyToId }
            inputMessageContent = TdApi.InputMessageText().apply {
                text = TdApi.FormattedText(body, emptyArray())
                clearDraft = true
            }
        }
        val sentFrom = chatId
        val appContext = applicationContext
        Tg.send(send) { result ->
            if (result is TdApi.Error) restoreUnsent(appContext, sentFrom, body, result.message)
        }
        composeField.view.setText("")
        Prefs.setDraft(this, chatId, "")
        replyToId = 0L
        stopComposing()
    }

    // ── Keys ────────────────────────────────────────────────────────────────────────────────

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuSheet.handleKeyDown(keyCode, event)) return true
        if (composeField.isComposing) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (!rowListView.canMoveSelection(-1)) loadHistory(initial = false) else rowListView.moveSelection(-1)
                updateHint()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!rowListView.canMoveSelection(1)) startComposing() else rowListView.moveSelection(1)
                updateHint()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                dayJumpIndex(rows, rowListView.selectedIndex, direction)?.let { rowListView.setSelectedIndex(it) }
                updateHint()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                openSelected()
                return true
            }
            KeyEvent.KEYCODE_POUND -> {
                toggleVoice()
                return true
            }
            KeyEvent.KEYCODE_STAR -> {
                starKey.onDown(event.repeatCount)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STAR) {
            starKey.onUp()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The call key is this screen's "write"/"send" key in every state, never the dialer: its
        // DOWN and UP are both taken, and it acts on UP (see consumeAndActOnUp).
        if (!composeField.isComposing && !menuSheet.isShowing &&
            consumeAndActOnUp(event, setOf(KeyEvent.KEYCODE_CALL)) { startComposing() }
        ) {
            return true
        }
        if (composeField.isComposing && consumeAndActOnUp(event, SUBMIT_KEYS) { sendComposed() }) return true
        if (composeField.isComposing && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    // With text, the device IME usually eats Back as a backspace before it gets
                    // here; on IMEs that don't, delete a character ourselves.
                    if (composeField.text.isNotEmpty()) {
                        composeField.text.delete(composeField.text.length - 1, composeField.text.length)
                    } else {
                        stopComposing()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> if (composeField.text.isEmpty()) {
                    stopComposing()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ── Actions ─────────────────────────────────────────────────────────────────────────────

    private fun openSelected() {
        val message = selectedMessage() ?: return
        when {
            Media.of(message.content) != null -> startActivity(MediaActivity.intentFor(this, chatId, message.id))
            Content.playable(message.content) != null -> toggleVoice()
            else -> startActivity(MessageDetailActivity.intentFor(this, chatId, message.id))
        }
    }

    private fun toggleVoice() {
        val message = selectedMessage() ?: return
        val playable = Content.playable(message.content) ?: return
        VoicePlayer.toggle(chatId, message.id, playable.file)
    }

    /** Message-specific actions first, so the ones that matter get the low number keys. */
    private fun openMenu() {
        val items = mutableListOf<MenuItem>()
        val message = selectedMessage()
        if (message != null) {
            val content = message.content
            when (messageKind(content)) {
                MessageKind.PHOTO -> items += MenuItem(if (content is TdApi.MessagePhoto) "Open photo" else "Open") {
                    startActivity(MediaActivity.intentFor(this, chatId, message.id))
                }
                MessageKind.VIDEO -> items += MenuItem("Play video") {
                    startActivity(MediaActivity.intentFor(this, chatId, message.id))
                }
                MessageKind.VOICE -> items += MenuItem(
                    if (VoicePlayer.isCurrent(chatId, message.id) && VoicePlayer.isPlaying) "Pause" else "Play",
                ) { toggleVoice() }
                else -> {}
            }
            items += MenuItem("Reply") { startReply(message.id) }
            val text = Content.text(content)
            if (text.isNotEmpty()) items += MenuItem("Copy text") { copy(text) }
            val links = Content.links(content)
            if (links.isNotEmpty()) items += MenuItem(if (links.size == 1) "Open link" else "Open link…") { openLinks(links) }
            Content.phoneNumbers(content).take(2).forEach { number ->
                items += MenuItem("Call $number") { openExternal(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")), "no phone app") }
            }
            Content.location(content)?.let { location ->
                items += MenuItem("Open map") { openMap(location) }
            }
            Content.savable(content)?.let { savable ->
                items += MenuItem("Save to phone") { saveFile(savable) }
            }
            items += MenuItem("Read in full") { startActivity(MessageDetailActivity.intentFor(this, chatId, message.id)) }
            items += MenuItem("Reply with voice") {
                startActivity(RecordVoiceActivity.intentFor(this, chatId, message.id))
            }
            if (message.sendingState is TdApi.MessageSendingStateFailed) {
                items += MenuItem("Resend") {
                    Tg.send(TdApi.ResendMessages(chatId, longArrayOf(message.id), null, 0))
                }
            }
            items += MenuItem("Delete…") { confirmDelete(message) }
        }
        items += MenuItem("Send photo or video") {
            startActivity(GalleryActivity.intentFor(this, chatId))
        }
        items += MenuItem("Record voice message") {
            startActivity(RecordVoiceActivity.intentFor(this, chatId, 0L))
        }
        items += MenuItem("Write a message") { startComposing() }
        items += MenuItem("Settings") { openNineSettings() }
        menuSheet.show(items)
    }

    private fun openLinks(links: List<String>) {
        if (links.size == 1) {
            openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(links.first())), "no browser on this phone")
            return
        }
        menuSheet.show(
            links.take(10).map { link ->
                MenuItem(link.removePrefix("https://").removePrefix("http://")) {
                    openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(link)), "no browser on this phone")
                }
            },
        )
    }

    /** A geo: URI, which any maps app on the phone answers; with none, the coordinates are at
     *  least copied so they can be pasted somewhere. */
    private fun openMap(location: TdApi.Location) {
        val coordinates = "${location.latitude},${location.longitude}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$coordinates?q=$coordinates"))
        if (!openExternal(intent, null)) {
            copy(Content.coordinates(location))
            hintBar.setText("no maps app · coordinates copied")
        }
    }

    /** @return false (after saying [missing], when given) if no app on the phone takes [intent]. */
    private fun openExternal(intent: Intent, missing: String?): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        missing?.let { hintBar.setText(it) }
        false
    }

    /** Downloads the file if it isn't yet (the hint bar counts it up), then copies it into the
     *  phone's shared folders — see [SaveToPhone]. */
    private fun saveFile(savable: Content.Savable) {
        val file = TgStore.latest(savable.file)
        val size = if (file.size > 0) file.size else file.expectedSize
        hintBar.setText(if (file.local.isDownloadingCompleted) "saving…" else "downloading ${Content.fileSize(size)}…")
        val appContext = applicationContext
        Tg.request<TdApi.File>(
            TdApi.DownloadFile(file.id, SAVE_DOWNLOAD_PRIORITY, 0, 0, true),
            onError = { if (!isDestroyed) hintBar.setText("couldn't download: ${it.message}") },
        ) { done ->
            if (!done.local.isDownloadingCompleted) {
                if (!isDestroyed) hintBar.setText("couldn't download the file")
                return@request
            }
            Async.run(
                background = { SaveToPhone.save(appContext, done.local.path, savable.fileName, savable.mimeType) },
                onResult = { where -> if (!isDestroyed) hintBar.setText(if (where != null) "saved to $where" else "couldn't save") },
            )
        }
    }

    private fun startReply(messageId: Long) {
        replyToId = messageId
        renderReplyBanner()
        startComposing()
    }

    /** Which of "for me"/"for everyone" exist depends on the chat and the message, and only TDLib
     *  knows — so the choice is shown once it has said. */
    private fun confirmDelete(message: TdApi.Message) {
        Tg.request<TdApi.MessageProperties>(TdApi.GetMessageProperties(chatId, message.id)) { properties ->
            val items = mutableListOf<MenuItem>()
            if (properties.canBeDeletedForAllUsers) {
                items += MenuItem("Delete for everyone") {
                    Tg.send(TdApi.DeleteMessages(chatId, longArrayOf(message.id), true))
                }
            }
            if (properties.canBeDeletedOnlyForSelf) {
                items += MenuItem("Delete for me") {
                    Tg.send(TdApi.DeleteMessages(chatId, longArrayOf(message.id), false))
                }
            }
            if (items.isEmpty()) {
                hintBar.setText("this message can't be deleted")
                return@request
            }
            items += MenuItem("Cancel") { menuSheet.hide() }
            menuSheet.show(items)
        }
    }

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Message", text))
        hintBar.setText("copied")
    }

    companion object {
        private const val EXTRA_CHAT_ID = "chat_id"

        /** The newest chat screen. Sending relaunches the screen (see [stopComposing]), so by the
         *  time TDLib refuses a message the instance that sent it is gone; this is where the text
         *  and the error go instead. */
        private var current: WeakReference<ChatActivity>? = null

        /** A message TDLib refused outright: put the text back where it was typed and say why.
         *  If that chat isn't on screen any more, it stays as the chat's draft. */
        private fun restoreUnsent(context: Context, chatId: Long, body: String, error: String) {
            val screen = current?.get()?.takeIf { it.chatId == chatId && !it.isFinishing && !it.isDestroyed }
            if (screen == null) {
                if (Prefs.draft(context, chatId).isBlank()) Prefs.setDraft(context, chatId, body)
                return
            }
            if (screen.composeField.text.isEmpty()) screen.composeField.view.setText(body)
            screen.hintBar.setText("not sent: $error")
        }

        fun intentFor(context: Context, chatId: Long): Intent =
            Intent(context, ChatActivity::class.java).putExtra(EXTRA_CHAT_ID, chatId)
    }
}
