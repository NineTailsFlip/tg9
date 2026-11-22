package dev.tg9.chats

import android.content.Context
import dev.tg9.Prefs
import android.graphics.Color
import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.dp

/** Two lines, the shape M9's thread rows use: the chat's name with its time, then the last
 *  message. The unread count sits at the start of the preview line, next to what it counts. */
fun chatRowHeightDp(fontSizeSp: Int): Int = fontSizeSp * 2 + 20

private fun previewTextSize(fontSizeSp: Int): Float = (fontSizeSp - 4).coerceAtLeast(10).toFloat()

/** Telegram's own accent, softened to the suite's pastel band like everything else on black. */
private const val UNREAD_COLOR = 0xFF8AB4F8.toInt()

/** One chat row, built once and re-bound in place.
 *
 *  RowListView re-binds every visible row on every key press, so a row that rebuilt its child
 *  views on each bind (as the first version did) allocated a dozen views per press — measurable
 *  input lag on a keypad phone's slow CPU. Binding now only sets text and colours, and skips even
 *  that when nothing changed. */
class ChatRowView @JvmOverloads constructor(
    context: Context,
    private val fontSizeSp: Int = Prefs.DEFAULT_MESSAGE_FONT_SIZE,
) : LinearLayout(context) {
    private val title = TextView(context)
    private val time = TextView(context)
    private val unread = TextView(context)
    private val preview = TextView(context)
    private var bound: ChatRow? = null
    private var boundSelected = false
    private var boundToday: LocalDate? = null

    init {
        orientation = VERTICAL
        val side = context.dp(Dimens.SIDE_PADDING_DP)
        val mainLine = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(side, 0, side, 0)
        }
        title.apply {
            textSize = fontSizeSp.toFloat()
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        time.apply {
            textSize = 13f
            setTextColor(Palette.GRAY_MUTED)
            setPadding(context.dp(8), 0, 0, 0)
        }
        mainLine.addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        mainLine.addView(time)
        addView(mainLine, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val previewLine = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(side, 0, side, context.dp(6))
        }
        unread.textSize = previewTextSize(fontSizeSp)
        preview.apply {
            textSize = previewTextSize(fontSizeSp)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        previewLine.addView(unread)
        previewLine.addView(preview, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(previewLine, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun bind(row: ChatRow, selected: Boolean, today: LocalDate) {
        if (row == bound && selected == boundSelected && today == boundToday) return
        if (row != bound || today != boundToday) {
            title.text = buildString {
                if (row.pinned) append("· ")
                append(row.title)
                if (row.muted) append(" (muted)")
            }
            time.text = formatChatTimestamp(row.date, today)
            if (row.hasUnread) {
                unread.visibility = View.VISIBLE
                unread.text = if (row.unreadCount > 0) "${row.unreadCount} " else "● "
                unread.setTextColor(if (row.muted) Palette.GRAY_MUTED else UNREAD_COLOR)
            } else {
                unread.visibility = View.GONE
            }
            preview.text = if (row.hasDraft) "Draft · ${row.preview}" else row.preview
            preview.setTextColor(if (row.hasDraft) Palette.GRAY_DIM else Palette.GRAY_MUTED)
        }
        title.setTextColor(if (selected) Color.WHITE else Palette.GRAY_DIM)
        title.paintFlags = if (selected) title.paintFlags or Paint.UNDERLINE_TEXT_FLAG else title.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        bound = row
        boundSelected = selected
        boundToday = today
    }
}
