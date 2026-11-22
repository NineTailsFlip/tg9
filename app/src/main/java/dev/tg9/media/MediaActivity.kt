package dev.tg9.media

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.VideoView
import dev.tg9.tg.Content
import dev.tg9.tg.Media
import dev.tg9.tg.Tg
import dev.tg9.tg.TgStore
import java.io.File
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.NineActivity
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.dp
import life.furrer.nine.core.util.Async
import org.drinkless.tdlib.TdApi

private const val DOWNLOAD_PRIORITY = 32
private const val ZOOM = 2.5f
private const val SEEK_MS = 10_000
private const val TICK_MS = 500L

/** Anything bigger waits for OK before it downloads: a keypad phone's data plan is often small,
 *  and a long video is easy to open by mistake. */
private const val AUTO_DOWNLOAD_BYTES = 15L * 1024 * 1024

/** One photo or video full screen on black — photos, videos, round video messages, GIFs,
 *  stickers, and pictures or videos sent as files (see [Media]).
 *
 *  While the file downloads, Telegram's tiny inline preview stands in for it, with the progress
 *  under it. A photo zooms with OK and pans with the D-pad while zoomed. A video plays as soon as
 *  it's there: OK pauses, Left/Right seek ten seconds. Up/Down step to the previous/next photo or
 *  video in the chat (older is up, as in the chat itself). */
class MediaActivity : NineActivity() {

    private val main = Handler(Looper.getMainLooper())

    private lateinit var image: ImageView
    private lateinit var video: VideoView
    private lateinit var status: TextView
    private lateinit var caption: TextView
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet

    private var chatId = 0L
    private var message: TdApi.Message? = null
    private var media: Media? = null

    /** What [image] shows, kept for the zoom matrix: the inline preview, then the real picture. */
    private var drawable: Drawable? = null
    private var localPath: String? = null

    /** A file this screen asked TDLib to download and hasn't yet seen finish; cancelled when the
     *  screen moves on, so walking past a big video doesn't keep downloading it. */
    private var downloadingFileId = 0
    private var awaitingDownloadOk = false
    private var videoReady = false
    private var navigating = false

    private var zoomed = false
    private var panX = 0f
    private var panY = 0f

    private val listener = Tg.Listener { update ->
        if (update is TdApi.UpdateFile && update.file.id == media?.file?.id) onFile(update.file)
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateHint()
            if (videoReady && video.isPlaying) main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0L)

        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setBackgroundColor(Palette.BLACK)
        }
        video = VideoView(this).apply {
            visibility = View.GONE
            setOnPreparedListener { player ->
                val current = media as? Media.Video ?: return@setOnPreparedListener
                player.isLooping = current.loop
                if (current.muted) player.setVolume(0f, 0f)
                videoReady = true
                image.visibility = View.GONE
                status.visibility = View.GONE
                start()
                startTicker()
            }
            setOnCompletionListener { updateHint() }
            setOnErrorListener { _, what, extra ->
                Log.w("TG9", "video playback failed: $what/$extra")
                showStatus("Can't play this video on this phone")
                visibility = View.GONE
                image.visibility = View.VISIBLE
                videoReady = false
                updateHint()
                true
            }
        }
        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Palette.GRAY_DIM)
            setShadowLayer(4f, 0f, 0f, Palette.BLACK)
            text = "Loading…"
        }
        caption = TextView(this).apply {
            textSize = 13f
            maxLines = 3
            setTextColor(Palette.GRAY_DIM)
            setPadding(dp(Dimens.SIDE_PADDING_DP), dp(4), dp(Dimens.SIDE_PADDING_DP), dp(4))
            visibility = View.GONE
        }
        hintBar = HintBar(this)
        menuSheet = MenuSheet(this)
        val stage = FrameLayout(this).apply {
            setBackgroundColor(Palette.BLACK)
            addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(caption)
            addView(hintBar)
        }
        setContentView(
            FrameLayout(this).apply {
                addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
        )
        image.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyMatrix() }
        Tg.addListener(listener)
        updateHint()

        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, 0L)
        Tg.request<TdApi.Message>(TdApi.GetMessage(chatId, messageId), onError = { showStatus(it.message) }) { show(it) }
    }

    override fun onPause() {
        super.onPause()
        if (videoReady && video.isPlaying) video.pause()
        main.removeCallbacks(ticker)
        updateHint()
    }

    override fun onDestroy() {
        super.onDestroy()
        Tg.removeListener(listener)
        main.removeCallbacks(ticker)
        cancelDownload()
        video.stopPlayback()
        (drawable as? AnimatedImageDrawable)?.stop()
        image.setImageDrawable(null)
        drawable = null
    }

    // ── Showing a message ───────────────────────────────────────────────────────────────────

    private fun show(next: TdApi.Message) {
        reset()
        message = next
        val found = Media.of(next.content)
        media = found
        val text = Content.text(next.content).trim()
        caption.text = text
        caption.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        if (found == null) {
            showStatus("Nothing to show for this message")
            updateHint()
            return
        }
        // Marks a round video message watched, as opening it in Telegram does.
        Tg.send(TdApi.OpenMessageContent(chatId, next.id))
        showMinithumbnail(found.minithumbnail)
        val file = TgStore.latest(found.file)
        when {
            file.local.isDownloadingCompleted -> open(file.local.path)
            fileBytes(file) > AUTO_DOWNLOAD_BYTES && !file.local.isDownloadingActive -> {
                awaitingDownloadOk = true
                showStatus("${kindName(found)} · ${Content.fileSize(fileBytes(file))}\nok to download")
            }
            else -> startDownload(file)
        }
        updateHint()
    }

    /** Back to an empty stage before the next message, so nothing of the previous one (its
     *  picture, its playing video, its download) carries over. */
    private fun reset() {
        cancelDownload()
        main.removeCallbacks(ticker)
        if (videoReady || video.visibility == View.VISIBLE) video.stopPlayback()
        video.visibility = View.GONE
        videoReady = false
        (drawable as? AnimatedImageDrawable)?.stop()
        drawable = null
        image.setImageDrawable(null)
        image.visibility = View.VISIBLE
        localPath = null
        awaitingDownloadOk = false
        zoomed = false
        panX = 0f
        panY = 0f
    }

    /** Telegram's ~40 px JPEG preview, stretched: blurry, but instantly there. */
    private fun showMinithumbnail(mini: TdApi.Minithumbnail?) {
        val data = mini?.data ?: return
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return
        setDrawable(BitmapDrawable(resources, bitmap))
    }

    private fun setDrawable(d: Drawable) {
        (drawable as? AnimatedImageDrawable)?.stop()
        drawable = d
        image.setImageDrawable(d)
        applyMatrix()
        (d as? AnimatedImageDrawable)?.start()
    }

    // ── Downloading ─────────────────────────────────────────────────────────────────────────

    private fun startDownload(file: TdApi.File) {
        awaitingDownloadOk = false
        downloadingFileId = file.id
        showStatus("Downloading…")
        Tg.send(TdApi.DownloadFile(file.id, DOWNLOAD_PRIORITY, 0, 0, false)) { result ->
            when (result) {
                is TdApi.File -> if (result.id == media?.file?.id) onFile(result)
                is TdApi.Error -> if (file.id == media?.file?.id) {
                    downloadingFileId = 0
                    showStatus("Couldn't download: ${result.message}")
                }
                else -> {}
            }
        }
        updateHint()
    }

    private fun onFile(file: TdApi.File) {
        if (localPath != null) return
        if (file.local.isDownloadingCompleted) {
            downloadingFileId = 0
            open(file.local.path)
            return
        }
        if (downloadingFileId != file.id) return
        val total = fileBytes(file)
        val done = file.local.downloadedPrefixSize
        showStatus(if (total > 0) "Downloading ${(done * 100 / total).coerceIn(0, 99)}% of ${Content.fileSize(total)}" else "Downloading…")
    }

    private fun cancelDownload() {
        val id = downloadingFileId
        if (id != 0) Tg.send(TdApi.CancelDownloadFile(id, false))
        downloadingFileId = 0
    }

    private fun fileBytes(file: TdApi.File): Long = if (file.size > 0) file.size else file.expectedSize

    // ── Opening the downloaded file ─────────────────────────────────────────────────────────

    private fun open(path: String) {
        localPath = path
        when (val current = media) {
            is Media.Image -> decodeImage(path)
            is Media.Video -> playVideo(path, current)
            null -> {}
        }
        updateHint()
    }

    /** Decoded off the main thread by the platform decoder (which also plays GIF and animated
     *  WebP), sampled down to about twice the screen so a zoomed photo keeps detail without
     *  holding a full-resolution bitmap in a small phone's memory. */
    private fun decodeImage(path: String) {
        val forMessage = message?.id
        val target = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 2
        showStatus(null)
        Async.run(
            background = {
                runCatching {
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
                        var sample = 1
                        while (maxOf(info.size.width, info.size.height) / (sample * 2) >= target) sample *= 2
                        decoder.setTargetSampleSize(sample)
                    }
                }.onFailure { Log.w("TG9", "decode image", it) }.getOrNull()
            },
            onResult = { decoded ->
                if (isDestroyed || message?.id != forMessage) return@run
                if (decoded == null) {
                    showStatus("Couldn't open this picture")
                } else {
                    setDrawable(decoded)
                }
            },
        )
    }

    private fun playVideo(path: String, current: Media.Video) {
        showStatus(null)
        // A silent GIF or video sticker mustn't pause the music someone is listening to.
        video.setAudioFocusRequest(if (current.muted) AudioManager.AUDIOFOCUS_NONE else AudioManager.AUDIOFOCUS_GAIN)
        if (!current.muted) VoicePlayer.stop()
        video.visibility = View.VISIBLE
        video.setVideoPath(path)
    }

    private fun showStatus(text: String?) {
        status.text = text.orEmpty()
        status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun kindName(media: Media): String = when (val content = message?.content) {
        is TdApi.MessageVideoNote -> "Video message"
        is TdApi.MessageAnimation -> "GIF"
        is TdApi.MessageSticker -> "Sticker"
        is TdApi.MessageDocument -> content.document.fileName.ifBlank { "File" }
        else -> if (media is Media.Video) "Video" else "Photo"
    }

    // ── Zoom ────────────────────────────────────────────────────────────────────────────────

    private fun applyMatrix() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        val vw = image.width.toFloat()
        val vh = image.height.toFloat()
        if (vw == 0f || vh == 0f || dw <= 0f || dh <= 0f) return
        val fit = minOf(vw / dw, vh / dh)
        val scale = if (zoomed) fit * ZOOM else fit
        val maxPanX = ((dw * scale - vw) / 2).coerceAtLeast(0f)
        val maxPanY = ((dh * scale - vh) / 2).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
        image.imageMatrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate((vw - dw * scale) / 2 + panX, (vh - dh * scale) / 2 + panY)
        }
    }

    private fun canZoom(): Boolean = media is Media.Image && localPath != null && drawable != null

    private fun toggleZoom() {
        if (!canZoom()) return
        zoomed = !zoomed
        panX = 0f
        panY = 0f
        applyMatrix()
        updateHint()
    }

    private fun pan(dx: Float, dy: Float) {
        panX += dx
        panY += dy
        applyMatrix()
    }

    // ── Video controls ──────────────────────────────────────────────────────────────────────

    private fun togglePlay() {
        if (!videoReady) return
        if (video.isPlaying) video.pause() else video.start()
        startTicker()
    }

    private fun seek(deltaMs: Int) {
        if (!videoReady) return
        val duration = video.duration.coerceAtLeast(0)
        video.seekTo((video.currentPosition + deltaMs).coerceIn(0, duration))
        updateHint()
    }

    private fun startTicker() {
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    // ── Previous / next ─────────────────────────────────────────────────────────────────────

    /** Finds the nearest older ([direction] -1) or newer (+1) message of the same kind. Asks for
     *  a window around the current message and picks the neighbour by id, which doesn't depend on
     *  whether TDLib counts the starting message in or out. */
    private fun step(direction: Int) {
        val current = message ?: return
        val filter = Media.neighbourFilter(current.content) ?: return
        if (navigating) return
        navigating = true
        Tg.request<TdApi.FoundChatMessages>(
            TdApi.SearchChatMessages(chatId, null, "", null, current.id, -5, 10, filter),
            onError = { navigating = false },
        ) { found ->
            navigating = false
            if (isDestroyed || message?.id != current.id) return@request
            val next = if (direction < 0) {
                found.messages.filter { it.id < current.id }.maxByOrNull { it.id }
            } else {
                found.messages.filter { it.id > current.id }.minByOrNull { it.id }
            }
            if (next == null) {
                hintBar.setText(if (direction < 0) "this is the oldest one" else "this is the newest one")
            } else {
                show(next)
            }
        }
    }

    // ── Hint and keys ───────────────────────────────────────────────────────────────────────

    private fun updateHint() {
        val canStep = Media.neighbourFilter(message?.content) != null
        val stepHint = if (canStep) " · ↑↓ prev/next" else ""
        hintBar.setText(
            when {
                awaitingDownloadOk -> "ok download$stepHint · ‹ back"
                localPath == null -> "* menu$stepHint · ‹ back"
                media is Media.Video && videoReady -> {
                    val position = "${Content.duration(video.currentPosition / 1000)} / ${Content.duration(video.duration / 1000)}"
                    "$position · ok ${if (video.isPlaying) "pause" else "play"} · ←→ 10 s$stepHint"
                }
                zoomed -> "✥ pan · ok zoom out · ‹ back"
                canZoom() -> "ok zoom$stepHint · * menu · ‹ back"
                else -> "* menu$stepHint · ‹ back"
            },
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuSheet.handleKeyDown(keyCode, event)) return true
        val panStep = image.width / 4f
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when {
                awaitingDownloadOk -> media?.let { startDownload(TgStore.latest(it.file)) }
                media is Media.Video -> togglePlay()
                else -> toggleZoom()
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                zoomed -> pan(panStep, 0f)
                videoReady -> seek(-SEEK_MS)
                else -> step(-1)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                zoomed -> pan(-panStep, 0f)
                videoReady -> seek(SEEK_MS)
                else -> step(1)
            }
            KeyEvent.KEYCODE_DPAD_UP -> if (zoomed) pan(0f, panStep) else step(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (zoomed) pan(0f, -panStep) else step(1)
            KeyEvent.KEYCODE_STAR -> openMenu()
            KeyEvent.KEYCODE_BACK -> finish()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun openMenu() {
        val current = media
        val items = mutableListOf<MenuItem>()
        if (awaitingDownloadOk && current != null) items += MenuItem("Download") { startDownload(TgStore.latest(current.file)) }
        if (videoReady) items += MenuItem(if (video.isPlaying) "Pause" else "Play") { togglePlay() }
        if (canZoom()) items += MenuItem(if (zoomed) "Zoom out" else "Zoom in") { toggleZoom() }
        if (localPath != null && current != null) items += MenuItem("Save to phone") { save(current) }
        if (Media.neighbourFilter(message?.content) != null) {
            items += MenuItem("Previous") { step(-1) }
            items += MenuItem("Next") { step(1) }
        }
        items += MenuItem("Back to chat") { finish() }
        menuSheet.show(items)
    }

    private fun save(current: Media) {
        val path = localPath ?: return
        val name = (message?.content as? TdApi.MessageDocument)?.document?.fileName?.takeIf { it.isNotBlank() } ?: File(path).name
        hintBar.setText("saving…")
        Async.run(
            background = { SaveToPhone.save(applicationContext, path, name, current.mimeType) },
            onResult = { where -> if (!isDestroyed) hintBar.setText(if (where != null) "saved to $where" else "couldn't save") },
        )
    }

    companion object {
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_MESSAGE_ID = "message_id"

        fun intentFor(context: Context, chatId: Long, messageId: Long): Intent =
            Intent(context, MediaActivity::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_MESSAGE_ID, messageId)
    }
}
