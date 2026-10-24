package com.readaloud.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/** Speaks a list of sentences one at a time so the UI can highlight [index] and resume from it. */
class Narrator(context: Context, private val onIndexChanged: (Int) -> Unit) {
    var index by mutableIntStateOf(0); private set
    var playing by mutableStateOf(false); private set
    var ready by mutableStateOf(false); private set
    var speed by mutableFloatStateOf(1f); private set

    /** [SystemClock.elapsedRealtime] at which the sleep timer pauses playback, or 0 when no timer is set. */
    var sleepEndsAt by mutableLongStateOf(0L); private set

    /** Fired after any play/pause/seek/advance so the media notification can refresh. */
    var onStateChanged: (() -> Unit)? = null

    val sentence: String get() = sentences.getOrElse(index) { "" }
    val size: Int get() = sentences.size

    private var sentences: List<String> = emptyList()
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    /** True while a phone call, alarm or another app has borrowed audio focus and we will carry on once it is returned. */
    private var resumeOnFocus = false

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                // Another app took audio for good: stop and let go of focus
                AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocus = false; pause() }
                // A call or alarm needs audio for a while: stop speaking but keep our place, and carry on afterwards
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (playing) { resumeOnFocus = true; haltSpeech() }
                AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocus) {
                    resumeOnFocus = false
                    playing = true
                    onStateChanged?.invoke()
                    speak()
                }
            }
        }
        .build()

    /** Unplugging headphones or dropping a Bluetooth speaker should not suddenly read aloud through the phone speaker. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (playing) pause() }
    }

    private var systemReady = false
    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        systemReady = status == TextToSpeech.SUCCESS
        if (kokoro == null) ready = systemReady
    }

    /** When set, sentences are voiced by Kokoro or Piper instead of the system engine. */
    private var kokoro: KokoroSpeaker? = null
    private var speaker = 0

    private val sleepRunnable = Runnable { sleepEndsAt = 0; pause() }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onError(utteranceId: String) { playing = false; onStateChanged?.invoke() }
            override fun onDone(utteranceId: String) { advance(utteranceId.toIntOrNull() ?: return) }
        })
        ContextCompat.registerReceiver(
            appContext, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** System voice only: called when the sentence at [finished] has been spoken to the end; moves on to the next one. */
    private fun advance(finished: Int) {
        val next = finished + 1
        if (!playing || finished != index) return
        if (next >= sentences.size) { finish(); return }
        index = next
        onIndexChanged(next)
        onStateChanged?.invoke()
        speak()
    }

    private fun finish() {
        playing = false
        resumeOnFocus = false
        audio.abandonAudioFocusRequest(focusRequest)
        onStateChanged?.invoke()
    }

    /** Switches voices. Takes effect on the next sentence if already playing; pass a null [engine] for the system voice. */
    fun useVoice(engine: KokoroSpeaker?, speakerId: Int = 0) {
        val old = kokoro
        kokoro = engine
        speaker = speakerId
        ready = engine != null || systemReady
        if (old != null && old !== engine) old.release()
        if (playing) speak()
    }

    fun load(sentences: List<String>, startAt: Int) {
        stop()
        this.sentences = sentences
        index = startAt.coerceIn(0, (sentences.size - 1).coerceAtLeast(0))
    }

    fun play() {
        if (!ready || sentences.isEmpty()) return
        if (audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        resumeOnFocus = false
        playing = true
        onStateChanged?.invoke()
        speak()
    }

    /** Stops the sound but leaves [playing] for the caller to decide; used while a call has our audio focus. */
    private fun haltSpeech() {
        playing = false
        tts.stop()
        kokoro?.cancel()
        onStateChanged?.invoke()
    }

    fun pause() {
        resumeOnFocus = false
        playing = false
        tts.stop()
        kokoro?.cancel()
        audio.abandonAudioFocusRequest(focusRequest)
        onIndexChanged(index)
        onStateChanged?.invoke()
    }

    fun stop() {
        resumeOnFocus = false
        playing = false
        tts.stop()
        kokoro?.cancel()
        audio.abandonAudioFocusRequest(focusRequest)
        setSleepTimer(0)
        onStateChanged?.invoke()
    }

    fun seek(to: Int) {
        index = to.coerceIn(0, (sentences.size - 1).coerceAtLeast(0))
        onIndexChanged(index)
        onStateChanged?.invoke()
        if (playing) speak()
    }

    fun changeSpeed(value: Float) {
        speed = value
        tts.setSpeechRate(value)
        if (playing) speak()
    }

    /** Pauses playback after [minutes]; 0 clears the timer. */
    fun setSleepTimer(minutes: Int) {
        handler.removeCallbacks(sleepRunnable)
        if (minutes <= 0) { sleepEndsAt = 0; return }
        val delay = minutes * 60_000L
        sleepEndsAt = SystemClock.elapsedRealtime() + delay
        handler.postDelayed(sleepRunnable, delay)
    }

    fun shutdown() {
        handler.removeCallbacks(sleepRunnable)
        runCatching { appContext.unregisterReceiver(noisy) }
        tts.stop(); tts.shutdown(); kokoro?.release()
    }

    private fun speak() {
        val engine = kokoro
        if (engine == null) {
            tts.setSpeechRate(speed)
            tts.speak(sentences[index], TextToSpeech.QUEUE_FLUSH, null, index.toString())
            return
        }
        tts.stop()
        // One pipeline runs on through the following sentences, so the highlight follows what is actually being heard
        engine.start(
            sentences, index, speaker, speed,
            onSentence = { i ->
                if (playing && i != index) { index = i; onIndexChanged(i); onStateChanged?.invoke() }
            },
            onEnd = { ok -> if (ok) finish() else { playing = false; onStateChanged?.invoke() } },
        )
    }
}
