package dev.tg9.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import dev.tg9.ui.KeyEditText
import dev.tg9.ui.SUBMIT_KEYS
import dev.tg9.ui.consumeAndActOnUp
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.tg9.chats.ChatListActivity
import dev.tg9.tg.Tg
import life.furrer.nine.core.settings.openNineSettings
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.NineActivity
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.dp
import org.drinkless.tdlib.TdApi

/** Signing in, one question per screen: phone number, then the code Telegram sends, then — only
 *  for accounts that have them — the two-step password, an email address/code, or a name for a
 *  brand-new account. Which question is shown is decided by TDLib's authorization state alone;
 *  this screen keeps no state machine of its own beyond "the user asked to change the number".
 *
 *  The one field on screen is focusable from the start, unlike the Nine apps' search fields: it
 *  is the only thing this screen is for, so there is no list for a stray first key to belong to. */
class LoginActivity : NineActivity() {

    private lateinit var title: TextView
    private lateinit var prompt: TextView
    private lateinit var field: KeyEditText
    private lateinit var error: TextView
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet

    /** The state the field currently answers, so a re-delivered identical state doesn't wipe what
     *  the user is typing. */
    private var shownStep: Step? = null
    private var busy = false

    /** Set from the menu: show the phone question again even though TDLib is waiting for a code. */
    private var changingNumber = false

    private val listener = Tg.Listener { update ->
        if (update is TdApi.UpdateAuthorizationState) render()
    }

    private enum class Step { CONNECTING, PHONE, CODE, PASSWORD, EMAIL, EMAIL_CODE, NAME, NO_API }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        title = TextView(this).apply {
            text = "TG9"
            textSize = 22f
            setTextColor(Palette.WHITE)
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(16), dp(Dimens.SIDE_PADDING_DP), dp(4))
        }
        prompt = TextView(this).apply {
            textSize = 15f
            setTextColor(Palette.GRAY_DIM)
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(8), dp(Dimens.SIDE_PADDING_DP), dp(8))
        }
        field = KeyEditText(this).apply {
            onPreImeKey = ::handleOwnKeys
            textSize = 20f
            setTextColor(Palette.WHITE)
            setHintTextColor(Palette.GRAY_FAINT)
            setBackgroundColor(Palette.BLACK)
            setSingleLine()
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(6), dp(Dimens.SIDE_PADDING_DP), dp(6))
        }
        error = TextView(this).apply {
            textSize = 13f
            setTextColor(Palette.RED)
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(4), dp(Dimens.SIDE_PADDING_DP), dp(4))
        }
        val rule = View(this).apply { setBackgroundColor(Palette.DIVIDER) }
        hintBar = HintBar(this)
        menuSheet = MenuSheet(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(prompt)
            addView(field)
            addView(rule, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            addView(error)
            addView(View(this@LoginActivity), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(hintBar)
        }
        setContentView(
            FrameLayout(this).apply {
                addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
        )
    }

    override fun onResume() {
        super.onResume()
        Tg.addListener(listener)
        render()
    }

    override fun onPause() {
        super.onPause()
        Tg.removeListener(listener)
    }

    private fun currentStep(): Step {
        if (!Tg.hasApiCredentials) return Step.NO_API
        return when (Tg.authState) {
            is TdApi.AuthorizationStateWaitPhoneNumber -> Step.PHONE
            is TdApi.AuthorizationStateWaitCode -> if (changingNumber) Step.PHONE else Step.CODE
            is TdApi.AuthorizationStateWaitPassword -> if (changingNumber) Step.PHONE else Step.PASSWORD
            is TdApi.AuthorizationStateWaitEmailAddress -> Step.EMAIL
            is TdApi.AuthorizationStateWaitEmailCode -> Step.EMAIL_CODE
            is TdApi.AuthorizationStateWaitRegistration -> Step.NAME
            else -> Step.CONNECTING
        }
    }

    private fun render() {
        if (Tg.isReady) {
            startActivity(Intent(this, ChatListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
            return
        }
        val step = currentStep()
        val state = Tg.authState
        prompt.text = when (step) {
            Step.NO_API -> "This build has no Telegram API ID. Add tg9.apiId and tg9.apiHash " +
                "(from my.telegram.org) to local.properties and rebuild."
            Step.CONNECTING -> "Connecting to Telegram…"
            Step.PHONE -> "Your phone number, with the country code:"
            Step.CODE -> codePrompt((state as TdApi.AuthorizationStateWaitCode).codeInfo)
            Step.PASSWORD -> {
                val hint = (state as TdApi.AuthorizationStateWaitPassword).passwordHint
                if (hint.isBlank()) "Your two-step verification password:" else "Your two-step verification password (hint: $hint):"
            }
            Step.EMAIL -> "Telegram asks for an email address for this account:"
            Step.EMAIL_CODE -> {
                val info = (state as TdApi.AuthorizationStateWaitEmailCode).codeInfo
                "The code sent to ${info.emailAddressPattern}:"
            }
            Step.NAME -> "New account. Your name:"
        }
        if (step != shownStep) {
            shownStep = step
            field.setText("")
            error.text = ""
            configureField(step)
        }
        updateHint(step)
    }

    private fun codePrompt(info: TdApi.AuthenticationCodeInfo): String = when (info.type) {
        is TdApi.AuthenticationCodeTypeTelegramMessage -> "The code Telegram sent to your other devices:"
        is TdApi.AuthenticationCodeTypeSms -> "The code sent by SMS to ${info.phoneNumber}:"
        is TdApi.AuthenticationCodeTypeCall -> "The code you'll hear in a call to ${info.phoneNumber}:"
        is TdApi.AuthenticationCodeTypeFragment -> "The code sent to your number on fragment.com:"
        else -> "The code sent to ${info.phoneNumber}:"
    }

    private fun configureField(step: Step) {
        val enabled = step != Step.CONNECTING && step != Step.NO_API
        field.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
        field.inputType = when (step) {
            Step.PHONE -> InputType.TYPE_CLASS_PHONE
            Step.CODE, Step.EMAIL_CODE -> InputType.TYPE_CLASS_NUMBER
            Step.PASSWORD -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            Step.EMAIL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
        }
        field.hint = when (step) {
            Step.PHONE -> "+7 900 000 00 00"
            Step.CODE, Step.EMAIL_CODE -> "12345"
            Step.NAME -> "First Last"
            else -> ""
        }
        if (enabled) field.requestFocus()
    }

    private fun updateHint(step: Step) {
        hintBar.setText(
            when {
                busy -> "sending…"
                step == Step.CONNECTING || step == Step.NO_API -> "* menu · ‹ exit"
                else -> "ok continue · * menu · ‹ exit"
            },
        )
    }

    private fun submit() {
        if (busy) return
        val step = currentStep()
        val value = field.text.toString().trim()
        if (value.isEmpty()) return
        val function: TdApi.Function<*> = when (step) {
            Step.PHONE -> TdApi.SetAuthenticationPhoneNumber(AuthText.normalizePhone(value), TdApi.PhoneNumberAuthenticationSettings())
            Step.CODE -> TdApi.CheckAuthenticationCode(value)
            Step.PASSWORD -> TdApi.CheckAuthenticationPassword(value)
            Step.EMAIL -> TdApi.SetAuthenticationEmailAddress(value)
            Step.EMAIL_CODE -> TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(value))
            Step.NAME -> {
                val first = value.substringBefore(' ')
                val last = value.substringAfter(' ', "").trim()
                TdApi.RegisterUser(first, last, false)
            }
            else -> return
        }
        busy = true
        error.text = ""
        updateHint(step)
        Tg.send(function) { result ->
            busy = false
            if (result is TdApi.Error) {
                error.text = AuthText.friendlyError(result)
            } else if (step == Step.PHONE) {
                changingNumber = false
            }
            render()
        }
    }

    private fun openMenu() {
        val items = mutableListOf<MenuItem>()
        val state = Tg.authState
        if (state is TdApi.AuthorizationStateWaitCode && !changingNumber) {
            if (state.codeInfo.nextType != null) {
                items += MenuItem("Send the code another way") {
                    Tg.send(TdApi.ResendAuthenticationCode(TdApi.ResendCodeReasonUserRequest())) { result ->
                        if (result is TdApi.Error) error.text = AuthText.friendlyError(result)
                    }
                }
            }
        }
        if ((state is TdApi.AuthorizationStateWaitCode || state is TdApi.AuthorizationStateWaitPassword) && !changingNumber) {
            items += MenuItem("Change phone number") {
                changingNumber = true
                render()
            }
        }
        items += MenuItem("Settings") { openNineSettings() }
        menuSheet.show(items)
    }

    /** The field owns most keys (it has focus), so OK, Back and `*` are taken here before the
     *  EditText and the IME see them — the same interception M9's reply row does. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleOwnKeys(event)) return true
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (field.text.isNotEmpty()) {
                    field.text.delete(field.text.length - 1, field.text.length)
                    return true
                }
                if (changingNumber) {
                    changingNumber = false
                    render()
                    return true
                }
                finishAffinity()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** The keys this screen owns, whether the field has focus (called from its pre-IME hook, so
     *  the T9 IME never turns OK into a space) or not (from [dispatchKeyEvent]). Each acts on its
     *  UP, see [consumeAndActOnUp]. */
    private fun handleOwnKeys(event: KeyEvent): Boolean {
        if (menuSheet.interceptKeyEvent(event)) return true
        if (consumeAndActOnUp(event, SUBMIT_KEYS) { submit() }) return true
        // A phone number or a code has no use for `*`; a password or a name might, so there `*`
        // only opens the menu when nothing is typed yet.
        val step = currentStep()
        if (step == Step.PHONE || step == Step.CODE || field.text.isEmpty()) {
            if (consumeAndActOnUp(event, setOf(KeyEvent.KEYCODE_STAR)) { openMenu() }) return true
        }
        return false
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, LoginActivity::class.java)
    }
}
