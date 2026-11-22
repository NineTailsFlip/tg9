package dev.tg9.ui

import android.content.Context
import android.view.KeyEvent
import android.widget.EditText

/** An EditText that lets its screen see keys *before* the input method does.
 *
 *  On keypad phones the T9 IME gets every key ahead of the app: an Activity's dispatchKeyEvent
 *  only runs for keys the IME didn't take. Confirmed on a user's phone: in a focused field the
 *  centre D-pad key typed a space instead of reaching the screen's "send". [onKeyPreIme] is the
 *  one hook Android calls ahead of the IME, so keys the screen owns (OK, the call key, `*` for
 *  the menu) are taken there.
 *
 *  [onPreImeKey] gets both ACTION_DOWN and ACTION_UP and returns true for keys it consumed. */
class KeyEditText(context: Context) : EditText(context) {
    var onPreImeKey: ((KeyEvent) -> Boolean)? = null

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean =
        onPreImeKey?.invoke(event) ?: super.onKeyPreIme(keyCode, event)
}

/** The keys that mean "send"/"continue" while typing. */
val SUBMIT_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_CALL)

/** Takes a key's DOWN (and its auto-repeats) and acts once, on its UP.
 *
 *  Acting on UP rather than DOWN matters for the call key: Android's fallback handler opens the
 *  dialer on the call key's *unhandled UP*, and a screen that acted on DOWN and then relaunched
 *  itself (as the chat screen does after sending) left that UP to land unhandled in the new window.
 *  @return true when [event] is one of [keys], i.e. always consumed. */
fun consumeAndActOnUp(event: KeyEvent, keys: Set<Int>, action: (keyCode: Int) -> Unit): Boolean {
    if (event.keyCode !in keys) return false
    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) action(event.keyCode)
    return true
}
