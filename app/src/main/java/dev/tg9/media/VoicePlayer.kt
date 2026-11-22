package dev.tg9.media

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.tg9.tg.Content
import dev.tg9.tg.Tg
import dev.tg9.tg.TgStore
import org.drinkless.tdlib.TdApi

/** Plays one voice message (or song, or audio file) at a time. Telegram voice messages are Opus in an Ogg container, which
 *  the platform's MediaPlayer decodes natively on every Android version TG9 runs on, so there is no
 *  decoder of our own.
 *
 *  Main thread only. A screen sets [onChange] while it's showing and gets a call on every state
 *  change and, while playing, twice a second for the progress text. */
object VoicePlayer {
    private const val TAG = "TG9"
    private const val TICK_MS = 500L
    private const val DOWNLOAD_PRIORITY = 32

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    var chatId: Long = 0L
        private set
    var messageId: Long = 0L
        private set
    /** True while a download for the requested message is still running. */
    var loading = false
        private set

    var onChange: () -> Unit = {}

    private val ticker = object : Runnable {
        override fun run() {
            onChange()
            if (player?.isPlaying == true) main.postDelayed(this, TICK_MS)
        }
    }

    fun isCurrent(chatId: Long, messageId: Long): Boolean =
        this.chatId == chatId && this.messageId == messageId && (player != null || loading)

    val isPlaying: Boolean get() = player?.isPlaying == true

    /** OK on a voice message or a song: start it, or pause/resume it if it's the current one.
     *  [audio] is the message's file (see [Content.playable]). */
    fun toggle(chatId: Long, messageId: Long, audio: TdApi.File) {
        val current = player
        if (current != null && isCurrent(chatId, messageId)) {
            if (current.isPlaying) current.pause() else current.start()
            main.removeCallbacks(ticker)
            main.post(ticker)
            return
        }
        stop()
        this.chatId = chatId
        this.messageId = messageId
        val file = TgStore.latest(audio)
        if (file.local.isDownloadingCompleted) {
            start(file.local.path)
            return
        }
        loading = true
        onChange()
        Tg.send(TdApi.DownloadFile(file.id, DOWNLOAD_PRIORITY, 0, 0, true)) { result ->
            loading = false
            // The user may have moved on to another message while this was downloading.
            if (this.chatId != chatId || this.messageId != messageId) return@send
            if (result is TdApi.File && result.local.isDownloadingCompleted) {
                start(result.local.path)
            } else {
                Log.w(TAG, "voice download failed: $result")
                onChange()
            }
        }
        // Marks the voice message as listened, as opening it in Telegram does.
        Tg.send(TdApi.OpenMessageContent(chatId, messageId))
    }

    private fun start(path: String) {
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            mp.setDataSource(path)
            mp.setOnCompletionListener { stop() }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "voice playback failed", e)
            mp.release()
            onChange()
            return
        }
        player = mp
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    fun stop() {
        main.removeCallbacks(ticker)
        player?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        player = null
        loading = false
        messageId = 0L
        onChange()
    }

    /** "0:03 / 0:12" for the current message, "loading…" while it downloads, or null. */
    fun progressText(durationSeconds: Int): String? {
        if (loading) return "loading…"
        val mp = player ?: return null
        val position = try { mp.currentPosition / 1000 } catch (_: IllegalStateException) { 0 }
        val state = if (mp.isPlaying) "" else "paused "
        return "$state${Content.duration(position)} / ${Content.duration(durationSeconds)}"
    }
}
