package dev.tg9.auth

import org.drinkless.tdlib.TdApi

/** The login screen's text handling, kept apart from the Activity so it is unit-tested. */
object AuthText {
    /** Keypads make `+` awkward to type, so a number without one is taken as international
     *  anyway; spaces, dashes and brackets are dropped, and a leading `00` means the same as `+`. */
    fun normalizePhone(input: String): String {
        val digits = input.filter { it.isDigit() }
        return "+" + if (input.trim().startsWith("+")) digits else digits.removePrefix("00")
    }

    fun friendlyError(error: TdApi.Error): String = when {
        error.message.contains("PHONE_NUMBER_INVALID") -> "That phone number isn't valid. Include the country code."
        error.message.contains("PHONE_CODE_INVALID") -> "Wrong code. Try again."
        error.message.contains("PHONE_CODE_EXPIRED") -> "The code expired. Use the menu to send a new one."
        error.message.contains("PASSWORD_HASH_INVALID") -> "Wrong password."
        error.message.contains("FLOOD_WAIT") || error.code == 429 -> "Too many attempts. Wait a while and try again."
        error.message.contains("API_ID_INVALID") -> "This build's Telegram API ID is invalid."
        else -> error.message
    }
}
