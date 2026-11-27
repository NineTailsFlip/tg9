package dev.tg9.gallery

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.g9.kit.GalleryItem
import dev.g9.kit.GalleryMarks
import dev.g9.kit.GalleryRow
import dev.g9.kit.GalleryStore
import dev.g9.kit.THUMB_DP
import dev.g9.kit.THUMB_HEADER_HEIGHT_DP
import dev.g9.kit.THUMB_ROW_HEIGHT_DP
import dev.g9.kit.ThumbRowView
import dev.g9.kit.ThumbnailLoader
import dev.g9.kit.buildGalleryRows
import dev.g9.kit.galleryDayJump
import dev.g9.kit.galleryMeta
import dev.g9.kit.galleryTitle
import dev.tg9.tg.Tg
import dev.tg9.tg.TgStore
import dev.tg9.ui.consumeAndActOnUp
import java.time.LocalDate
import java.util.concurrent.Executors
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.NineActivity
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.PermissionScreen
import life.furrer.nine.core.ui.RowBinder
import life.furrer.nine.core.ui.RowListView
import life.furrer.nine.core.ui.dp
import org.drinkless.tdlib.TdApi

private const val REQUEST_MEDIA = 300
/** Telegram takes at most ten photos and videos in one album. */
private const val MAX_ALBUM = 10

/** The phone's photos and videos for sending into a chat, in the suite's one column: newest
 *  first under day headers, each row a small thumbnail with its time, kind, folder and size.
 *
 *  Up/Down move, Left/Right jump a day, OK opens the selection full screen (where Up/Down keep
 *  stepping), `#` marks up to ten for one album, and the call key — or OK in the full-screen
 *  view — sends: the marked ones if any, else the one on screen. */
class GalleryActivity : NineActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var thumbs: ThumbnailLoader

    private var chatId = 0L
    private var replyTo = 0L

    private var rows: List<GalleryRow> = emptyList()
    private var byId: Map<Long, GalleryItem> = emptyMap()
    private var rowOfId: Map<Long, Int> = emptyMap()
    private val marks = GalleryMarks(MAX_ALBUM)
    private var loaded = false
    private var sending = false

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private lateinit var rowListView: RowListView
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet
    private lateinit var permissionScreen: PermissionScreen

    private lateinit var preview: FrameLayout
    private lateinit var previewImage: ImageView
    private lateinit var previewLabel: TextView
    private lateinit var previewHint: HintBar
    private var previewItem: GalleryItem? = null

    private val binder = object : RowBinder {
        override fun rowCount(): Int = rows.size
        override fun createRowView(context: Context): View = ThumbRowView(context)
        override fun bind(view: View, index: Int, selected: Boolean) {
            val rowView = view as ThumbRowView
            when (val row = rows[index]) {
                is GalleryRow.DayHeader -> rowView.bindHeader(row.label)
                is GalleryRow.Item -> rowView.bindRow(
                    galleryTitle(row.item),
                    galleryMeta(row.item),
                    selected,
                    marks.isMarked(row.item.id),
                    thumbs.get(row.item),
                )
            }
        }
        override fun rowHeight(index: Int): Int = if (rows[index] is GalleryRow.DayHeader) THUMB_HEADER_HEIGHT_DP else THUMB_ROW_HEIGHT_DP
        override fun isSelectable(index: Int): Boolean = rows[index].isSelectable()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0L)
        replyTo = intent.getLongExtra(EXTRA_REPLY_TO, 0L)
        thumbs = ThumbnailLoader(this, dp(THUMB_DP)) { rowListView.notifyDataChanged() }

        title = TextView(this).apply {
            textSize = 18f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Palette.WHITE)
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(10), dp(Dimens.SIDE_PADDING_DP), 0)
            text = "Send to ${TgStore.chats[chatId]?.title.orEmpty()}".trim()
        }
        subtitle = TextView(this).apply {
            textSize = 12f
            maxLines = 1
            setTextColor(Palette.GRAY_MUTED)
            setPadding(dp(Dimens.SIDE_PADDING_DP), 0, dp(Dimens.SIDE_PADDING_DP), dp(6))
        }
        rowListView = RowListView(this).apply { setBinder(binder) }
        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Palette.GRAY_DIM)
            text = "Loading photos…"
        }
        hintBar = HintBar(this)
        menuSheet = MenuSheet(this)
        permissionScreen = PermissionScreen(this).apply {
            onGrant = { requestPermissions(GalleryStore.PERMISSIONS, REQUEST_MEDIA) }
            onLeave = { finish() }
        }
        buildPreview()

        val listArea = FrameLayout(this).apply {
            addView(rowListView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(subtitle)
            addView(listArea, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hintBar)
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Palette.BLACK)
                val full = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                addView(column, full)
                addView(preview, FrameLayout.LayoutParams(full))
                addView(menuSheet, FrameLayout.LayoutParams(full))
                addView(permissionScreen, FrameLayout.LayoutParams(full))
            },
        )
        if (GalleryStore.hasAccess(this)) load() else askForAccess()
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbs.close()
        worker.shutdown()
    }

    private fun askForAccess() {
        permissionScreen.show(
            "Telegram needs to see your photos and videos to send them. Only the ones you choose here " +
                "leave the phone.\n\nYou can allow all of them, or pick some.",
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_MEDIA) return
        if (GalleryStore.hasAccess(this)) {
            permissionScreen.hide()
            load()
        }
    }

    private fun hasPartialAccess(): Boolean = GalleryStore.hasPartialAccess(this)

    // ── Loading ─────────────────────────────────────────────────────────────────────────────

    private fun load() {
        val appContext = applicationContext
        worker.execute {
            val items = GalleryStore.load(appContext)
            main.post {
                if (isDestroyed) return@post
                val keep = (rows.getOrNull(rowListView.selectedIndex) as? GalleryRow.Item)?.item?.id
                rows = buildGalleryRows(items, LocalDate.now())
                byId = items.associateBy { it.id }
                rowOfId = rows.withIndex().mapNotNull { (i, row) -> (row as? GalleryRow.Item)?.let { it.item.id to i } }.toMap()
                thumbs.rowOf = rowOfId
                loaded = true
                rowListView.notifyDataChanged()
                val keepRow = keep?.let { rowOfId[it] }
                if (keepRow != null) rowListView.setSelectedIndex(keepRow) else rowListView.selectFirstSelectable()
                render()
            }
        }
    }

    // ── Rendering ───────────────────────────────────────────────────────────────────────────

    private fun selectedItem(): GalleryItem? = (rows.getOrNull(rowListView.selectedIndex) as? GalleryRow.Item)?.item

    private fun render() {
        thumbs.focusRow = rowListView.selectedIndex
        val count = byId.size
        subtitle.text = when {
            !loaded -> ""
            marks.size > 0 -> "${marks.size} marked of $count"
            hasPartialAccess() -> "$count you allowed Telegram to see"
            else -> "$count photos and videos"
        }
        subtitle.visibility = if (subtitle.text.isEmpty()) View.GONE else View.VISIBLE
        status.visibility = if (loaded && rows.isNotEmpty()) View.GONE else View.VISIBLE
        if (loaded && rows.isEmpty()) status.text = "No photos or videos on this phone"
        // While sending, the hint bar is the progress line (see setSendingText); leave it be.
        if (!sending) hintBar.setText(
            when {
                !loaded || rows.isEmpty() -> "* menu · ‹ back"
                marks.size > 0 -> "call send ${marks.size} · # mark · ok view"
                else -> "ok view · # mark · call send · * menu"
            },
        )
        renderPreview()
    }

    // ── Full-screen preview ─────────────────────────────────────────────────────────────────

    private fun buildPreview() {
        previewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Palette.BLACK)
        }
        previewLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(Palette.WHITE)
            setShadowLayer(4f, 0f, 0f, Palette.BLACK)
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(8), dp(Dimens.SIDE_PADDING_DP), dp(8))
        }
        previewHint = HintBar(this)
        preview = FrameLayout(this).apply {
            setBackgroundColor(Palette.BLACK)
            visibility = View.GONE
            addView(
                LinearLayout(this@GalleryActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        FrameLayout(this@GalleryActivity).apply {
                            addView(previewImage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                            addView(previewLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
                    )
                    addView(previewHint)
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
    }

    private fun openPreview(item: GalleryItem) {
        previewItem = item
        preview.visibility = View.VISIBLE
        previewImage.setImageBitmap(thumbs.peek(item.id))
        val appContext = applicationContext
        val longest = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        worker.execute {
            val bitmap = GalleryStore.preview(appContext, item, longest)
            main.post {
                if (!isDestroyed && previewItem?.id == item.id && bitmap != null) previewImage.setImageBitmap(bitmap)
            }
        }
        renderPreview()
    }

    private fun closePreview() {
        previewItem = null
        preview.visibility = View.GONE
        previewImage.setImageDrawable(null)
        render()
    }

    private fun renderPreview() {
        val item = previewItem ?: return
        val mark = if (marks.isMarked(item.id)) "✓ marked\n" else ""
        previewLabel.text = "$mark${galleryTitle(item)}\n${galleryMeta(item)}"
        previewHint.setText(
            when {
                sending -> "sending…"
                marks.size > 0 -> "ok send ${marks.size} marked · # mark · ↑↓"
                else -> "ok send · # mark · ↑↓ prev/next · ‹ back"
            },
        )
    }

    /** Up/Down in the preview: the list's selection moves with it, so Back lands on what was
     *  last looked at. */
    private fun stepPreview(direction: Int) {
        if (!rowListView.canMoveSelection(direction)) return
        rowListView.moveSelection(direction)
        selectedItem()?.let { openPreview(it) }
        render()
    }

    // ── Marking and sending ─────────────────────────────────────────────────────────────────

    private fun toggleMark(item: GalleryItem?) {
        item ?: return
        when (marks.toggle(item.id)) {
            GalleryMarks.Result.FULL -> {
                hintBar.setText("up to ${MAX_ALBUM} in one album")
                previewHint.setText("up to ${MAX_ALBUM} in one album")
                return
            }
            else -> {}
        }
        rowListView.notifyDataChanged()
        render()
    }

    /** The marked ones if there are any, else [fallback]. */
    private fun sendChosen(fallback: GalleryItem?) {
        val chosen = if (marks.size > 0) marks.resolve(byId) else listOfNotNull(fallback)
        send(chosen)
    }

    private fun send(items: List<GalleryItem>) {
        if (sending || items.isEmpty()) return
        sending = true
        setSendingText(if (items.size == 1) "preparing…" else "preparing 1 of ${items.size}…")
        val appContext = applicationContext
        worker.execute {
            val prepared = runCatching {
                items.mapIndexed { i, item ->
                    if (i > 0) main.post { setSendingText("preparing ${i + 1} of ${items.size}…") }
                    Outgoing.prepare(appContext, item)
                }
            }
            main.post {
                if (isDestroyed) return@post
                prepared
                    .onSuccess { post(it) }
                    .onFailure {
                        sending = false
                        setSendingText("couldn't prepare: ${it.message ?: it.javaClass.simpleName}")
                    }
            }
        }
    }

    private fun post(contents: List<TdApi.InputMessageContent>) {
        val function = Outgoing.sendFunction(chatId, replyTo, contents)
        setSendingText("sending…")
        // Back to the chat once TDLib has taken it: from there on it shows as a pending message,
        // and its upload progress is TDLib's business. A refusal keeps the gallery open to say so.
        Tg.send(function) { result ->
            if (isDestroyed) return@send
            if (result is TdApi.Error) {
                sending = false
                setSendingText("not sent: ${result.message}")
            } else {
                finish()
            }
        }
    }

    private fun setSendingText(text: String) {
        hintBar.setText(text)
        previewHint.setText(text)
    }

    // ── Keys ────────────────────────────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The call key sends, never the dialer — taken on both DOWN and UP, acted on at UP.
        if (!menuSheet.isShowing && !permissionScreen.isShowing &&
            consumeAndActOnUp(event, setOf(KeyEvent.KEYCODE_CALL)) { sendChosen(previewItem ?: selectedItem()) }
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (permissionScreen.handleKeyDown(keyCode)) return true
        if (menuSheet.handleKeyDown(keyCode, event)) return true
        previewItem?.let { item ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> sendChosen(item)
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> stepPreview(-1)
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> stepPreview(1)
                KeyEvent.KEYCODE_POUND -> toggleMark(item)
                KeyEvent.KEYCODE_STAR -> openMenu()
                KeyEvent.KEYCODE_BACK -> closePreview()
                else -> return super.onKeyDown(keyCode, event)
            }
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> rowListView.moveSelection(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> rowListView.moveSelection(1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                galleryDayJump(rows, rowListView.selectedIndex, direction)?.let { rowListView.setSelectedIndex(it) }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> selectedItem()?.let { openPreview(it) }
            KeyEvent.KEYCODE_POUND -> toggleMark(selectedItem())
            KeyEvent.KEYCODE_STAR -> openMenu()
            KeyEvent.KEYCODE_BACK -> finish()
            else -> return super.onKeyDown(keyCode, event)
        }
        render()
        return true
    }

    private fun openMenu() {
        val item = previewItem ?: selectedItem()
        val items = mutableListOf<MenuItem>()
        if (item != null) {
            items += MenuItem(if (item.isVideo) "Send this video" else "Send this photo") { send(listOf(item)) }
        }
        if (marks.size > 0) items += MenuItem("Send ${marks.size} marked as an album") { send(marks.resolve(byId)) }
        if (item != null) {
            items += MenuItem(if (marks.isMarked(item.id)) "Unmark" else "Mark for an album") { toggleMark(item) }
            if (previewItem == null) items += MenuItem("View full screen") { openPreview(item) }
        }
        if (marks.size > 0) {
            items += MenuItem("Clear marks") {
                marks.clear()
                rowListView.notifyDataChanged()
                render()
            }
        }
        if (loaded && hasPartialAccess()) {
            items += MenuItem("Choose which photos Telegram sees") { requestPermissions(GalleryStore.PERMISSIONS, REQUEST_MEDIA) }
        }
        items += MenuItem("Back to chat") { finish() }
        menuSheet.show(items)
    }

    companion object {
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_REPLY_TO = "reply_to"

        fun intentFor(context: Context, chatId: Long, replyTo: Long = 0L): Intent =
            Intent(context, GalleryActivity::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_REPLY_TO, replyTo)
    }
}
