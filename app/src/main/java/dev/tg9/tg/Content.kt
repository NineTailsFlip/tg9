package dev.tg9.tg

import dev.g9.kit.Format
import java.util.Locale
import org.drinkless.tdlib.TdApi

/** What a message is, reduced to what a one-column, text-only screen can show of it. Pure, so the
 *  wording is unit-tested rather than eyeballed on the device. */
object Content {

    /** The message's own text: a text message's body, or a media message's caption. Empty when
     *  there's none. */
    fun text(content: TdApi.MessageContent?): String = when (content) {
        is TdApi.MessageText -> content.text.text
        is TdApi.MessagePhoto -> content.caption.text
        is TdApi.MessageVideo -> content.caption.text
        is TdApi.MessageVoiceNote -> content.caption.text
        is TdApi.MessageDocument -> content.caption.text
        is TdApi.MessageAudio -> content.caption.text
        is TdApi.MessageAnimation -> content.caption.text
        is TdApi.MessagePoll -> pollText(content.poll)
        else -> ""
    }

    /** The options, one per line, with their share of the votes once those can be seen. */
    private fun pollText(poll: TdApi.Poll): String {
        val options = poll.options.joinToString("\n") { option ->
            if (poll.canSeeResults) "• ${option.text.text} — ${option.votePercentage}%" else "• ${option.text.text}"
        }
        val votes = when (poll.totalVoterCount) {
            0 -> "no votes yet"
            1 -> "1 vote"
            else -> "${poll.totalVoterCount} votes"
        }
        return "$options\n$votes" + if (poll.isClosed) ", closed" else ""
    }

    /** A bracketed label for whatever isn't text — "[Photo]", "[Voice 0:12]" — or null for a plain
     *  text message. Brackets, not icons: the suite draws no glyphs it can't type. */
    fun label(content: TdApi.MessageContent?): String? = when (content) {
        null, is TdApi.MessageText -> null
        is TdApi.MessagePhoto -> "[Photo]"
        is TdApi.MessageVoiceNote -> "[Voice ${duration(content.voiceNote.duration)}]"
        is TdApi.MessageVideo -> "[Video ${duration(content.video.duration)}${sizeSuffix(content.video.video)}]"
        is TdApi.MessageVideoNote -> "[Video message ${duration(content.videoNote.duration)}]"
        is TdApi.MessageAudio -> "[Audio: ${listOf(content.audio.performer, content.audio.title).filter { it.isNotBlank() }.joinToString(" – ").ifBlank { content.audio.fileName }}]"
        is TdApi.MessageDocument -> "[File: ${content.document.fileName.ifBlank { "unnamed" }}${sizeSuffix(content.document.document)}]"
        is TdApi.MessageAnimation -> "[GIF]"
        is TdApi.MessageSticker -> "[Sticker ${content.sticker.emoji}]".replace(" ]", "]")
        is TdApi.MessageLocation -> "[Location ${coordinates(content.location)}]"
        is TdApi.MessageVenue -> "[Place: ${listOf(content.venue.title, content.venue.address).filter { it.isNotBlank() }.joinToString(", ").ifBlank { coordinates(content.venue.location) }}]"
        is TdApi.MessageContact -> "[Contact: ${listOf(listOf(content.contact.firstName, content.contact.lastName).filter { it.isNotBlank() }.joinToString(" "), content.contact.phoneNumber).filter { it.isNotBlank() }.joinToString(", ")}]"
        is TdApi.MessagePoll -> "[Poll: ${content.poll.question.text}]"
        is TdApi.MessageCall -> if (content.isVideo) "[Video call]" else "[Call]"
        is TdApi.MessagePinMessage -> "[Pinned a message]"
        is TdApi.MessageChatAddMembers -> "[Joined the group]"
        is TdApi.MessageChatJoinByLink -> "[Joined by link]"
        is TdApi.MessageChatDeleteMember -> "[Left the group]"
        is TdApi.MessageChatChangeTitle -> "[Renamed the group to ${content.title}]"
        is TdApi.MessageBasicGroupChatCreate -> "[Created the group]"
        is TdApi.MessageSupergroupChatCreate -> "[Created the group]"
        is TdApi.MessageContactRegistered -> "[Joined Telegram]"
        is TdApi.MessageUnsupported -> "[Not supported by TG9]"
        else -> "[${content.javaClass.simpleName.removePrefix("Message")}]"
    }

    /** One line for the chat list: the label, then the text, whichever exist. */
    fun summary(content: TdApi.MessageContent?): String {
        val label = label(content)
        val text = text(content).replace('\n', ' ').trim()
        return when {
            label == null -> text
            text.isEmpty() -> label
            else -> "$label $text"
        }
    }

    /** The full body a message row and the detail screen show: label on its own line above the
     *  text, so a caption reads as what it is. */
    fun body(content: TdApi.MessageContent?): String {
        val label = label(content)
        val text = text(content)
        return when {
            label == null -> text
            text.isEmpty() -> label
            else -> "$label\n$text"
        }
    }

    fun coordinates(location: TdApi.Location): String =
        String.format(Locale.US, "%.5f, %.5f", location.latitude, location.longitude)

    private fun sizeSuffix(file: TdApi.File): String {
        val bytes = if (file.size > 0) file.size else file.expectedSize
        return if (bytes > 0) ", ${fileSize(bytes)}" else ""
    }

    /** "820 KB", "4.1 MB" — what a download will cost on a keypad phone's data plan. */
    fun fileSize(bytes: Long): String = Format.fileSize(bytes)

    /** Something [dev.tg9.media.VoicePlayer] plays in place: a voice message, a song, or an audio
     *  file. */
    data class Playable(val file: TdApi.File, val durationSeconds: Int)

    fun playable(content: TdApi.MessageContent?): Playable? = when (content) {
        is TdApi.MessageVoiceNote -> Playable(content.voiceNote.voice, content.voiceNote.duration)
        is TdApi.MessageAudio -> Playable(content.audio.audio, content.audio.duration)
        is TdApi.MessageDocument -> content.document.takeIf { it.mimeType.startsWith("audio/") }?.let { Playable(it.document, 0) }
        else -> null
    }

    /** A file the "Save to phone" menu item can copy out of TDLib's storage, with the name and
     *  type it should get there. */
    data class Savable(val file: TdApi.File, val fileName: String, val mimeType: String)

    fun savable(content: TdApi.MessageContent?): Savable? = when (content) {
        is TdApi.MessageDocument -> content.document.let {
            Savable(it.document, it.fileName.ifBlank { "file" }, it.mimeType.ifBlank { "application/octet-stream" })
        }
        is TdApi.MessageAudio -> content.audio.let {
            Savable(it.audio, it.fileName.ifBlank { "audio" }, it.mimeType.ifBlank { "audio/mpeg" })
        }
        is TdApi.MessageVideo -> content.video.let {
            Savable(it.video, it.fileName.ifBlank { "video.mp4" }, it.mimeType.ifBlank { "video/mp4" })
        }
        is TdApi.MessageVoiceNote -> Savable(content.voiceNote.voice, "voice.ogg", content.voiceNote.mimeType.ifBlank { "audio/ogg" })
        else -> null
    }

    private fun formatted(content: TdApi.MessageContent?): TdApi.FormattedText? = when (content) {
        is TdApi.MessageText -> content.text
        is TdApi.MessagePhoto -> content.caption
        is TdApi.MessageVideo -> content.caption
        is TdApi.MessageVoiceNote -> content.caption
        is TdApi.MessageDocument -> content.caption
        is TdApi.MessageAudio -> content.caption
        is TdApi.MessageAnimation -> content.caption
        else -> null
    }

    /** Every link in the text, in order and without repeats: bare URLs as written, and the
     *  targets of text links. Those without a scheme get https://, as Telegram itself does. */
    fun links(content: TdApi.MessageContent?): List<String> {
        val text = formatted(content) ?: return emptyList()
        return text.entities.orEmpty().mapNotNull { entity ->
            when (val type = entity.type) {
                is TdApi.TextEntityTypeUrl -> slice(text.text, entity)
                is TdApi.TextEntityTypeTextUrl -> type.url
                else -> null
            }
        }.map { if (it.contains("://") || it.startsWith("mailto:") || it.startsWith("tg:")) it else "https://$it" }.distinct()
    }

    /** Numbers worth a "Call" item: a shared contact's, and any phone number Telegram marked in
     *  the text. */
    fun phoneNumbers(content: TdApi.MessageContent?): List<String> {
        val fromContact = (content as? TdApi.MessageContact)?.contact?.phoneNumber?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("+")) it else "+$it" }
        val text = formatted(content)
        val inText = text?.entities.orEmpty().filter { it.type is TdApi.TextEntityTypePhoneNumber }.mapNotNull { slice(text!!.text, it) }
        return (listOfNotNull(fromContact) + inText).distinct()
    }

    fun location(content: TdApi.MessageContent?): TdApi.Location? = when (content) {
        is TdApi.MessageLocation -> content.location
        is TdApi.MessageVenue -> content.venue.location
        else -> null
    }

    /** TDLib's entity offsets count UTF-16 code units, the same as a Java string. */
    private fun slice(text: String, entity: TdApi.TextEntity): String? =
        if (entity.offset >= 0 && entity.length > 0 && entity.offset + entity.length <= text.length) {
            text.substring(entity.offset, entity.offset + entity.length)
        } else {
            null
        }

    /** "0:07", "1:05", "1:02:03". */
    fun duration(seconds: Int): String = Format.duration(seconds)

    /** The largest photo size that still fits within [maxSide] px on its longer side, else the
     *  smallest one — a keypad phone's screen is ~320 px wide, and downloading the 1280 px
     *  original to show it at a quarter of that is wasted data. */
    fun bestPhotoSize(photo: TdApi.Photo, maxSide: Int): TdApi.PhotoSize? {
        val sizes = photo.sizes.sortedBy { maxOf(it.width, it.height) }
        return sizes.lastOrNull { maxOf(it.width, it.height) <= maxSide } ?: sizes.firstOrNull()
    }
}
