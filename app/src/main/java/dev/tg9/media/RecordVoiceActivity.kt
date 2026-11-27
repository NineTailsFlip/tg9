package dev.tg9.media

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.tg9.tg.Content
import dev.tg9.tg.Tg
import java.io.File
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.NineActivity
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.PermissionScreen
import org.drinkless.tdlib.TdApi

private const val REQUEST_RECORD_AUDIO = 200
/** Anything shorter is a mis-press, not a message. */
private const val MIN_DURATION_MS = 700L
private const val MAX_DURATION_S = 15 * 60
private const val OLD_RECORDING_MS = 24 * 60 * 60 * 1000L

/** Records a voice message and sends it. Starts recording as soon as it opens (the menu item that
 *  got here *was* the "record" press); OK stops and sends, Back throws it away.
 *
 *  Recorded straight to Ogg/Opus, which is what Telegram voice messages are, so TDLib sends the
 *  file as-is with no transcoding. */
class RecordVoiceActivity : NineActivity() {

    private val main = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAt = 0L
    private val amplitudes = ArrayList<Int>()

    private lateinit var timer: TextView
    private lateinit var hintBar: HintBar
    private lateinit var permissionScreen: PermissionScreen

    private val ticker = object : Runnable {
        override fun run() {
            val seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
            timer.text = "● ${Content.duration(seconds)}"
            recorder?.let { amplitudes += runCatching { it.maxAmplitude }.getOrDefault(0) }
            if (seconds >= MAX_DURATION_S) stopAndSend() else main.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        timer = TextView(this).apply {
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Palette.RED)
            text = "● 0:00"
        }
        val label = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Palette.GRAY_DIM)
            text = if (intent.getLongExtra(EXTRA_REPLY_TO, 0L) != 0L) "Recording a voice reply" else "Recording a voice message"
        }
        hintBar = HintBar(this)
        permissionScreen = PermissionScreen(this).apply {
            onGrant = { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO) }
            onLeave = { finish() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(label)
            addView(timer)
        }
        setContentView(
            FrameLayout(this).apply {
                addView(
                    LinearLayout(this@RecordVoiceActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(column, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                        addView(hintBar)
                    },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                addView(permissionScreen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
        )
        hintBar.setText("ok send · ‹ discard")
        if (hasPermission()) start() else permissionScreen.show(
            "Telegram needs the microphone to record a voice message. It is used only while this screen is open.",
        )
    }

    private fun hasPermission() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO && hasPermission()) {
            permissionScreen.hide()
            start()
        }
    }

    private fun start() {
        // Stop whatever voice message is playing: recording over your own speaker is never wanted.
        VoicePlayer.stop()
        deleteOldRecordings()
        val file = File(cacheDir, "voice-${System.currentTimeMillis()}.ogg")
        val mr = MediaRecorder(this)
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            mr.setAudioChannels(1)
            mr.setAudioSamplingRate(48_000)
            mr.setAudioEncodingBitRate(32_000)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
        } catch (e: Exception) {
            Log.w("TG9", "recorder", e)
            mr.release()
            hintBar.setText("couldn't start the microphone · ‹ back")
            return
        }
        recorder = mr
        output = file
        startedAt = SystemClock.elapsedRealtime()
        main.post(ticker)
    }

    /** Sent recordings stay where they were recorded: TDLib uploads straight from that path and
     *  keeps pointing at it afterwards, so the file can't go as soon as the message is sent. A day
     *  later the upload is long finished, and TDLib downloads the voice message again if it's
     *  played once more. */
    private fun deleteOldRecordings() {
        val cutoff = System.currentTimeMillis() - OLD_RECORDING_MS
        cacheDir.listFiles { f -> f.name.startsWith("voice-") && f.name.endsWith(".ogg") && f.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    /** Stops the recorder; true when there is something worth sending. */
    private fun stopRecorder(): Boolean {
        main.removeCallbacks(ticker)
        val mr = recorder ?: return false
        recorder = null
        val ok = runCatching { mr.stop() }.isSuccess
        mr.release()
        return ok && SystemClock.elapsedRealtime() - startedAt >= MIN_DURATION_MS
    }

    private fun stopAndSend() {
        val file = output
        if (!stopRecorder() || file == null) {
            discard()
            return
        }
        val duration = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt().coerceAtLeast(1)
        val chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0L)
        val replyTo = intent.getLongExtra(EXTRA_REPLY_TO, 0L)
        Tg.send(
            TdApi.SendMessage().apply {
                this.chatId = chatId
                if (replyTo != 0L) this.replyTo = TdApi.InputMessageReplyToMessage().apply { messageId = replyTo }
                inputMessageContent = TdApi.InputMessageVoiceNote().apply {
                    voiceNote = TdApi.InputVoiceNote(TdApi.InputFileLocal(file.absolutePath), duration, Waveform.encode(amplitudes))
                    caption = TdApi.FormattedText("", emptyArray())
                }
            },
        )
        finish()
    }

    private fun discard() {
        stopRecorder()
        output?.delete()
        finish()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (permissionScreen.handleKeyDown(keyCode)) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_CALL -> stopAndSend()
            KeyEvent.KEYCODE_BACK -> discard()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    /** Leaving the screen (a call, the home key) is treated as Back: a recording that kept running
     *  invisibly would be worse than one that's lost. */
    override fun onPause() {
        super.onPause()
        if (recorder != null && !isFinishing) discard()
    }

    companion object {
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val EXTRA_REPLY_TO = "reply_to"

        fun intentFor(context: Context, chatId: Long, replyTo: Long): Intent =
            Intent(context, RecordVoiceActivity::class.java)
                .putExtra(EXTRA_CHAT_ID, chatId)
                .putExtra(EXTRA_REPLY_TO, replyTo)
    }
}
