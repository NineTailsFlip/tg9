package dev.tg9.chats

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.tg9.Prefs
import dev.tg9.auth.LoginActivity
import dev.tg9.chat.ChatActivity
import dev.tg9.notify.Notifications
import dev.tg9.tg.Tg
import dev.tg9.tg.TgStore
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
import life.furrer.nine.core.ui.SearchField
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.ui.relaunch
import java.time.LocalDate
import org.drinkless.tdlib.TdApi

/** Up/Down/OK are list navigation and `*`/`#` are this screen's menu and unread filter, so none
 *  of them may reach the search field — the same reservation M9's conversation list makes. */
private val RESERVED_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_POUND,
)

/** How many chats one loadChats asks for, and how close to the end of what's loaded the
 *  selection may get before the next page is asked for. */
private const val CHAT_PAGE = 50
private const val LOAD_MORE_MARGIN = 10

/** TG9's launcher entry: the main chat list in Telegram's order, filtered live by name. Sends to
 *  the login screen whenever TDLib isn't signed in.
 *
 *  Key handling is M9's conversation list's, piece for piece, because it has the same live
 *  [SearchField] and therefore the same on-device IME constraints (see that screen and
 *  [SearchField]'s own documentation). */
class ChatListActivity : NineActivity() {

    private var rows: List<ChatRow> = emptyList()
    private var unreadOnly = false
    private var allChatsLoaded = false
    private var loadingChats = false
    private var refreshPosted = false

    /** Read once per screen, not once per row per key press. A change in Settings recreates the
     *  screen in onResume. */
    private var fontSize = Prefs.DEFAULT_MESSAGE_FONT_SIZE
    private var today: LocalDate = LocalDate.now()
    private var drafts: Set<Long> = emptySet()

    /** What the current rows were built from, so a rebuild with nothing new to show is skipped. */
    private var builtFrom: List<Any?> = emptyList()

    /** When the last D-pad key went down. Background rebuilds wait until the user pauses: a
     *  rebuild landing between two presses is exactly what made scrolling feel late. */
    private var lastKeyAt = 0L

    private val starKey = LongPressKey(::openMenu) { openNineSettings() }

    private lateinit var rowListView: RowListView
    private lateinit var searchField: SearchField
    private lateinit var status: TextView
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet

    private val binder = object : RowBinder {
        override fun rowCount(): Int = rows.size
        override fun createRowView(context: Context): View = ChatRowView(context, fontSize)
        override fun bind(view: View, index: Int, selected: Boolean) {
            (view as ChatRowView).bind(rows[index], selected, today)
        }
        override fun rowHeight(index: Int): Int = rowHeightDp
    }
    private val rowHeightDp: Int get() = chatRowHeightDp(fontSize)

    private val listener = Tg.Listener { update ->
        when (update) {
            is TdApi.UpdateAuthorizationState -> if (Tg.needsLogin) goToLogin() else loadMoreChats()
            is TdApi.UpdateConnectionState -> updateStatus()
            is TdApi.UpdateNewChat, is TdApi.UpdateChatLastMessage, is TdApi.UpdateChatPosition,
            is TdApi.UpdateChatReadInbox, is TdApi.UpdateChatTitle, is TdApi.UpdateChatIsMarkedAsUnread,
            is TdApi.UpdateChatNotificationSettings, is TdApi.UpdateChatDraftMessage, is TdApi.UpdateUser,
            -> scheduleRefresh()
            else -> {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        unreadOnly = intent.getBooleanExtra(EXTRA_UNREAD_ONLY, false)
        fontSize = Prefs.messageFontSize(this)

        searchField = SearchField(this).apply {
            onQueryChanged = { rebuildRows(resetSelection = true) }
            onDeactivated = { restartFresh() }
            editText.apply {
                hint = "Type to search"
                textSize = 16f
                setTextColor(Palette.WHITE)
                setHintTextColor(Palette.GRAY_FAINT)
                setPadding(dp(Dimens.SIDE_PADDING_DP), dp(10), dp(Dimens.SIDE_PADDING_DP), dp(6))
            }
        }
        rowListView = RowListView(this).apply { setBinder(binder) }
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Palette.GRAY_MUTED)
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(4), dp(Dimens.SIDE_PADDING_DP), dp(4))
            visibility = View.GONE
        }
        hintBar = HintBar(this)
        menuSheet = MenuSheet(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchField.editText)
            addView(rowListView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(status)
            addView(hintBar)
        }
        setContentView(
            FrameLayout(this).apply {
                addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            },
        )
        updateHint()
    }

    override fun onResume() {
        super.onResume()
        Tg.addListener(listener)
        Notifications.ensurePermission(this)
        if (Tg.needsLogin) {
            goToLogin()
            return
        }
        if (Prefs.messageFontSize(this) != fontSize) {
            restartFresh()
            return
        }
        drafts = TgStore.chats.keys.filterTo(HashSet()) { Prefs.draft(this, it).isNotBlank() }
        builtFrom = emptyList()
        rebuildRows(resetSelection = false)
        updateStatus()
        loadMoreChats()
    }

    override fun onPause() {
        super.onPause()
        Tg.removeListener(listener)
        searchField.onPause()
        starKey.reset()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) searchField.onWindowFocusGained()
    }

    private fun goToLogin() {
        startActivity(LoginActivity.intent(this))
        finish()
    }

    private fun restartFresh() {
        relaunch(Intent(this, ChatListActivity::class.java).putExtra(EXTRA_UNREAD_ONLY, unreadOnly))
    }

    /** loadChats only makes TDLib *announce* more chats (as updateNewChat/updateChatPosition);
     *  the rows come from TgStore when those land. A 404 means there are no more. */
    private fun loadMoreChats() {
        if (!Tg.isReady || loadingChats || allChatsLoaded) return
        loadingChats = true
        Tg.send(TdApi.LoadChats(TdApi.ChatListMain(), CHAT_PAGE)) { result ->
            loadingChats = false
            if (result is TdApi.Error && result.code == 404) allChatsLoaded = true
            scheduleRefresh()
        }
    }

    /** Coalesces the burst of updates TDLib sends at startup (hundreds of chats, users and
     *  positions) into one rebuild per frame. */
    private fun scheduleRefresh() {
        if (refreshPosted) return
        refreshPosted = true
        rowListView.postDelayed(refreshRunnable, REFRESH_DELAY_MS)
    }

    private val refreshRunnable: Runnable = object : Runnable {
        override fun run() {
            val idleFor = android.os.SystemClock.uptimeMillis() - lastKeyAt
            if (idleFor < KEY_IDLE_MS) {
                rowListView.postDelayed(this, KEY_IDLE_MS - idleFor)
                return
            }
            refreshPosted = false
            rebuildRows(resetSelection = false)
        }
    }

    private fun rebuildRows(resetSelection: Boolean) {
        val key = listOf(TgStore.chatListVersion, TgStore.usersVersion, searchField.query, unreadOnly, LocalDate.now())
        if (key == builtFrom && !resetSelection) return
        builtFrom = key
        today = LocalDate.now()
        val selectedChat = rows.getOrNull(rowListView.selectedIndex)?.chatId
        rows = buildChatRows(
            TgStore.mainChats(),
            searchField.query,
            unreadOnly,
            TgStore::isGroup,
            TgStore::senderName,
            drafts,
        )
        rowListView.notifyDataChanged()
        if (resetSelection) {
            rowListView.selectFirstSelectable()
        } else if (selectedChat != null) {
            // Keep the user on the same chat when a new message moves it up the list.
            val index = rows.indexOfFirst { it.chatId == selectedChat }
            if (index >= 0) rowListView.setSelectedIndex(index)
        }
        updateStatus()
        updateHint()
    }

    private fun updateStatus() {
        val text = when {
            Tg.connectionState is TdApi.ConnectionStateWaitingForNetwork -> "Waiting for network…"
            Tg.connectionState is TdApi.ConnectionStateConnecting -> "Connecting…"
            Tg.connectionState is TdApi.ConnectionStateUpdating -> "Updating…"
            rows.isEmpty() && (loadingChats || !Tg.isReady) -> "Loading chats…"
            rows.isEmpty() && searchField.query.isNotEmpty() -> "No chat matches"
            rows.isEmpty() && unreadOnly -> "No unread chats"
            else -> ""
        }
        status.text = text
        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateHint() {
        hintBar.setText(
            if (searchField.query.isEmpty()) {
                "* menu · ${if (unreadOnly) "# all" else "# unread"} · ok open"
            } else {
                "‹ clear · ok open"
            },
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) lastKeyAt = event.eventTime
        if (menuSheet.interceptKeyEvent(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (searchField.handleBack(event) { super.dispatchKeyEvent(it) }) return true
            if (searchField.query.isEmpty()) finish() else searchField.clear()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_STAR) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> starKey.onDown(event.repeatCount)
                KeyEvent.ACTION_UP -> starKey.onUp()
            }
            return true
        }
        if (searchField.handleKeyEvent(event, RESERVED_KEYS)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in RESERVED_KEYS) handleReservedKeyDown(event.keyCode)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleReservedKeyDown(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> rowListView.moveSelection(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                rowListView.moveSelection(1)
                if (rowListView.selectedIndex >= rows.size - LOAD_MORE_MARGIN) loadMoreChats()
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> openSelected()
            KeyEvent.KEYCODE_POUND -> {
                unreadOnly = !unreadOnly
                rebuildRows(resetSelection = true)
            }
        }
    }

    private fun selectedRow(): ChatRow? = rows.getOrNull(rowListView.selectedIndex)

    private fun openSelected() {
        val row = selectedRow() ?: return
        startActivity(ChatActivity.intentFor(this, row.chatId))
    }

    private fun openMenu() {
        val items = mutableListOf<MenuItem>()
        selectedRow()?.let { row ->
            if (row.hasUnread) {
                items += MenuItem("Mark as read") { markRead(row) }
            } else {
                items += MenuItem("Mark as unread") {
                    Tg.send(TdApi.ToggleChatIsMarkedAsUnread(row.chatId, true))
                }
            }
            items += if (row.muted) {
                MenuItem("Unmute") { setMuted(row.chatId, false) }
            } else {
                MenuItem("Mute") { setMuted(row.chatId, true) }
            }
        }
        items += MenuItem(if (unreadOnly) "Show all chats" else "Show unread only") {
            unreadOnly = !unreadOnly
            rebuildRows(resetSelection = true)
        }
        items += MenuItem("Settings") { openNineSettings() }
        items += MenuItem("Log out") { confirmLogOut() }
        menuSheet.show(items)
    }

    /** Reading a chat "from the list" means: everything in it up to its last message is seen, and
     *  a manual unread mark is gone. */
    private fun markRead(row: ChatRow) {
        val chat = TgStore.chats[row.chatId] ?: return
        chat.lastMessage?.let { last ->
            Tg.send(TdApi.ViewMessages(row.chatId, longArrayOf(last.id), TdApi.MessageSourceChatList(), true))
        }
        if (chat.isMarkedAsUnread) Tg.send(TdApi.ToggleChatIsMarkedAsUnread(row.chatId, false))
        Tg.send(TdApi.ReadAllChatMentions(row.chatId))
    }

    /** "Mute" is forever, Telegram's own long-mute value; "Unmute" goes back to the default for
     *  that kind of chat rather than forcing notifications on. */
    private fun setMuted(chatId: Long, muted: Boolean) {
        val chat = TgStore.chats[chatId] ?: return
        val settings = chat.notificationSettings
        settings.useDefaultMuteFor = !muted
        settings.muteFor = if (muted) Int.MAX_VALUE else 0
        Tg.send(TdApi.SetChatNotificationSettings(chatId, settings))
    }

    private fun confirmLogOut() {
        menuSheet.show(
            listOf(
                MenuItem("Log out of Telegram on this phone?") { Tg.logOut() },
                MenuItem("Cancel") { menuSheet.hide() },
            ),
        )
    }

    companion object {
        private const val EXTRA_UNREAD_ONLY = "unread_only"
        /** At most ~3 background rebuilds a second, however fast TDLib sends updates. */
        private const val REFRESH_DELAY_MS = 300L
        private const val KEY_IDLE_MS = 600L
    }
}
