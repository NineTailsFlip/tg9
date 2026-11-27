package dev.tg9.chat

import android.content.Context
import dev.tg9.Prefs
import android.graphics.Color
import android.graphics.Paint
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.util.DateFmt

// Row geometry follows M9's MessageRowRenderer, which settled these on hardware: rows are
// measured with a StaticLayout over the real text rather than estimated, capped at a few lines
// (the rest is one OK away, in MessageDetailActivity), with a little slack so descenders survive.
private const val HEADER_ROW_HEIGHT_DP = 24
private const val META_HEIGHT_DP = 18
private const val SENDER_HEIGHT_DP = 16
private const val REPLY_HEIGHT_DP = 16
private const val MAX_LINES = 6
private const val VERTICAL_SLACK_DP = 4
private const val SMALL_TEXT_SP = 11f

/** Telegram's own accent for names in groups, pastel like the rest of the suite. */
private const val SENDER_COLOR = 0xFF8AB4F8.toInt()

/** How many texts' heights a [Measurer] remembers. */
private const val MAX_MEASURED = 2000

/** Measures message text the way [MessageRowView] lays it out. A chat rebuilds its rows on every
 *  new message, edit or read receipt, and a StaticLayout per message per rebuild was most of the
 *  cost of a long chat; a text keeps its height, so each one is measured once. */
private class Measurer(context: Context, val fontSizeSp: Int) {
    private val paint = TextPaint(TextView(context).apply { textSize = fontSizeSp.toFloat() }.paint)
    private val widthPx = (context.resources.displayMetrics.widthPixels - 2 * context.dp(Dimens.SIDE_PADDING_DP)).coerceAtLeast(1)
    private val heights = object : LinkedHashMap<String, Int>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > MAX_MEASURED
    }

    fun textHeightPx(text: String): Int = heights.getOrPut(text) {
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx).build()
        val lines = layout.lineCount.coerceIn(1, MAX_LINES)
        layout.getLineBottom(lines - 1)
    }
}

/** The last screen's measurer. Weakly: holding the screen itself kept a closed chat in memory. */
private var cached: Pair<WeakReference<Context>, Measurer>? = null

private fun measurer(context: Context, fontSizeSp: Int): Measurer {
    cached?.let { (c, m) -> if (c.get() === context && m.fontSizeSp == fontSizeSp) return m }
    return Measurer(context, fontSizeSp).also { cached = WeakReference(context) to it }
}

fun messageRowHeightDp(context: Context, row: MessageRow, fontSizeSp: Int): Int = when (row) {
    is MessageRow.DayHeader -> HEADER_ROW_HEIGHT_DP
    is MessageRow.Entry -> {
        val density = context.resources.displayMetrics.density
        val textDp = (measurer(context, fontSizeSp).textHeightPx(row.body) / density).toInt()
        textDp + META_HEIGHT_DP + VERTICAL_SLACK_DP +
            (if (row.sender.isNotEmpty()) SENDER_HEIGHT_DP else 0) +
            (if (row.replyTo.isNotEmpty()) REPLY_HEIGHT_DP else 0)
    }
}

/** One message row (or day header), built once and re-bound in place — see ChatRowView for why
 *  rebuilding child views on every bind was too slow on a keypad phone. */
class MessageRowView @JvmOverloads constructor(
    context: Context,
    private val fontSizeSp: Int = Prefs.DEFAULT_MESSAGE_FONT_SIZE,
) : LinearLayout(context) {
    private val side = context.dp(Dimens.SIDE_PADDING_DP)
    private val header = TextView(context).apply {
        textSize = SMALL_TEXT_SP
        letterSpacing = 0.11f
        gravity = Gravity.CENTER
        setTextColor(Palette.GRAY_FAINT)
    }
    private val sender = small(SENDER_COLOR)
    private val reply = small(Palette.GRAY_MUTED)
    private val body = TextView(context).apply {
        textSize = fontSizeSp.toFloat()
        maxLines = MAX_LINES
        ellipsize = TextUtils.TruncateAt.END
        paintFlags = paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
        setPadding(side, 0, side, 0)
    }
    private val meta = small(Palette.GRAY_MUTED).apply { setPadding(side, 0, side, context.dp(2)) }

    private var bound: MessageRow? = null
    private var boundSelected = false
    private var boundPlaying: String? = null

    init {
        orientation = VERTICAL
        val wrap = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(header, LayoutParams(wrap))
        addView(sender, LayoutParams(wrap))
        addView(reply, LayoutParams(wrap))
        addView(body, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(meta, LayoutParams(wrap))
    }

    private fun small(color: Int) = TextView(context).apply {
        textSize = SMALL_TEXT_SP
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(color)
        setPadding(side, 0, side, 0)
    }

    /** [playing] is the voice-message progress text ("0:03 / 0:12") when this row is the one
     *  being played, shown in front of the time. */
    fun bind(row: MessageRow, selected: Boolean, playing: String?) {
        if (row == bound && selected == boundSelected && playing == boundPlaying) return
        bound = row
        boundSelected = selected
        boundPlaying = playing
        when (row) {
            is MessageRow.DayHeader -> {
                header.text = row.label
                header.visibility = VISIBLE
                sender.visibility = GONE
                reply.visibility = GONE
                body.visibility = GONE
                meta.visibility = GONE
            }
            is MessageRow.Entry -> bindEntry(row, selected, playing)
        }
    }

    /** Incoming flush left, outgoing flush right, no bubbles — direction is carried by alignment
     *  alone, exactly as in M9. */
    private fun bindEntry(row: MessageRow.Entry, selected: Boolean, playing: String?) {
        val alignment = if (row.outgoing) Gravity.END else Gravity.START
        header.visibility = GONE
        sender.visibility = if (row.sender.isNotEmpty()) VISIBLE else GONE
        sender.text = row.sender
        sender.gravity = alignment
        reply.visibility = if (row.replyTo.isNotEmpty()) VISIBLE else GONE
        reply.text = row.replyTo
        reply.gravity = alignment
        body.visibility = VISIBLE
        body.text = row.body
        body.gravity = alignment
        body.setTextColor(if (selected) Color.WHITE else Palette.GRAY_DIM)
        meta.visibility = VISIBLE
        meta.gravity = alignment
        meta.text = buildString {
            if (playing != null) append("▶ $playing  ")
            append(DateFmt.formatTime(row.date * 1000L))
            if (row.edited) append(" edited")
            val glyph = sendStatusGlyph(row.status)
            if (glyph.isNotEmpty()) append(" $glyph")
        }
        meta.setTextColor(if (row.status == SendStatus.FAILED) Palette.RED else Palette.GRAY_MUTED)
    }
}
