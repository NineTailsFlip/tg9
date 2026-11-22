package dev.tg9.chat

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import dev.tg9.ui.KeyEditText
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.dp

/** The reply row at the bottom of a chat — M9's ComposeField: focusable only while composing, so
 *  the key that enters it is a deliberate one and the list keeps every other key. */
class ComposeField(context: Context, hint: String, fontSizeSp: Int, onEdited: () -> Unit) {
    val view: KeyEditText = KeyEditText(context).apply {
        this.hint = hint
        textSize = fontSizeSp.toFloat()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        maxLines = 4
        setTextColor(Palette.WHITE)
        setHintTextColor(Palette.GRAY_FAINT)
        setBackgroundColor(Palette.BLACK)
        gravity = Gravity.START
        setPadding(dp(Dimens.SIDE_PADDING_DP), dp(6), dp(Dimens.SIDE_PADDING_DP), dp(12))
        isFocusable = false
        isFocusableInTouchMode = false
        addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = onEdited()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    var isComposing = false
        private set

    val text: Editable get() = view.text

    fun start(): Boolean {
        if (isComposing) return false
        isComposing = true
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.requestFocus()
        view.setSelection(view.text.length)
        return true
    }

    fun stop(): Boolean {
        if (!isComposing) return false
        isComposing = false
        view.clearFocus()
        view.isFocusable = false
        view.isFocusableInTouchMode = false
        return true
    }
}
