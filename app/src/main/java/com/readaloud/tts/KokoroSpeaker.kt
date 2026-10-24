package com.readaloud.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "Kokoro"

/**
 * Speaks a run of sentences through a sherpa-onnx voice (Kokoro or Piper) as a two-stage pipeline: one thread generates
 * audio for sentence N+1, N+2... while another plays sentence N, so the pause between sentences disappears whenever the
 * voice generates at least as fast as it plays. [start] and [cancel] may be called from any thread.
 */
class KokoroSpeaker(private val tts: OfflineTts) {
    val speakerCount: Int get() = tts.numSpeakers()

    private sealed interface Item
    private class Audio(val sentence: Int, val samples: FloatArray) : Item
    private class Finished(val ok: Boolean) : Item

    private val producer = Executors.newSingleThreadExecutor()
    private val consumer = Executors.newSingleThreadExecutor()
    private val lock = Any()
    @Volatile private var token = 0
    private var track: AudioTrack? = null

    /**
     * Speaks [sentences] from index [from]. [onSentence] fires as each sentence starts to be heard (not when it is generated);
     * [onEnd] gets true when the last sentence has been heard, false if generation failed. A cancelled run reports neither.
     */
    fun start(
        sentences: List<String>, from: Int, speaker: Int, speed: Float,
        onSentence: (Int) -> Unit, onEnd: (Boolean) -> Unit,
    ) {
        cancel() // frees the audio track and tells the previous run's threads to stop
        val mine = synchronized(lock) { ++token }
        val queue = LinkedBlockingQueue<Item>()
        val queuedSamples = AtomicInteger(0)
        val rate = tts.sampleRate()
        producer.execute { produce(sentences, from, speaker, speed, mine, queue, queuedSamples, rate) }
        consumer.execute { consume(mine, rate, queue, queuedSamples, onSentence, onEnd) }
    }

    /** One sentence on its own, for voice previews. */
    fun speak(text: String, speaker: Int, speed: Float, onDone: (Boolean) -> Unit) =
        start(listOf(text), 0, speaker, speed, {}, onDone)

    private fun produce(
        sentences: List<String>, from: Int, speaker: Int, speed: Float, mine: Int,
        queue: LinkedBlockingQueue<Item>, queuedSamples: AtomicInteger, rate: Int,
    ) {
        val t0 = System.currentTimeMillis()
        try {
            for (i in from until sentences.size) {
                // Stay a little way ahead, not the whole book ahead
                while (mine == token && queuedSamples.get() > rate * 45) Thread.sleep(40)
                if (mine != token) return
                // Must be an object, not a lambda: the JNI side looks up invoke([F)Ljava/lang/Integer; by exact signature,
                // and a lambda desugared by D8 only has the generic invoke(Object), which aborts the process.
                val index = i
                val sink = object : (FloatArray) -> Int {
                    override fun invoke(samples: FloatArray): Int {
                        if (mine != token) return 0
                        queue.put(Audio(index, samples.copyOf()))
                        queuedSamples.addAndGet(samples.size)
                        return 1
                    }
                }
                // The model handles only a few hundred tokens per call, so long sentences are fed in pieces
                for (piece in chunks(sentences[i])) {
                    if (mine != token) return
                    tts.generateWithCallback(piece, speaker, speed, sink)
                }
                Log.d(TAG, "generated #$i (${sentences[i].length} chars) +${System.currentTimeMillis() - t0}ms queued=${queuedSamples.get() * 1000L / rate}ms")
            }
            queue.put(Finished(true))
        } catch (e: Throwable) {
            Log.e(TAG, "generate failed for speaker=$speaker", e)
            queue.put(Finished(false))
        }
    }

    private fun consume(
        mine: Int, rate: Int, queue: LinkedBlockingQueue<Item>, queuedSamples: AtomicInteger,
        onSentence: (Int) -> Unit, onEnd: (Boolean) -> Unit,
    ) {
        val out = newTrack(rate)
        synchronized(lock) { if (mine == token) track = out else { out.release(); return } }
        var written = 0L
        var announced = -1
        val starts = java.util.concurrent.ConcurrentLinkedDeque<Pair<Long, Int>>() // (first frame of the sentence, sentence index)
        // The highlight should move the moment a sentence is heard. A blocking write can sit for seconds while a chunk drains,
        // so a separate ticker watches the playback position instead of checking between writes. The small lead covers the
        // delay between the track reporting a frame and the speaker playing it.
        val lead = rate / 20
        val ticking = java.util.concurrent.atomic.AtomicBoolean(true)
        fun announceDue() {
            val head = runCatching { out.playbackHeadPosition.toLong() }.getOrDefault(0L) + lead
            while (mine == token) {
                val first = starts.peekFirst() ?: break
                if (first.first > head) break
                starts.pollFirst()
                onSentence(first.second)
            }
        }
        val ticker = Thread {
            while (ticking.get() && mine == token) { announceDue(); try { Thread.sleep(25) } catch (_: InterruptedException) { break } }
        }.apply { isDaemon = true; start() }
        var result: Boolean? = null
        try {
            out.play()
            while (mine == token && result == null) {
                val item = queue.poll(50, TimeUnit.MILLISECONDS)
                when (item) {
                    null -> {}
                    is Audio -> {
                        if (item.sentence != announced) { announced = item.sentence; starts.addLast(written to item.sentence) }
                        val w = out.write(item.samples, 0, item.samples.size, AudioTrack.WRITE_BLOCKING)
                        if (w < 0) { Log.w(TAG, "AudioTrack write failed: $w"); result = false } else written += w
                        queuedSamples.addAndGet(-item.samples.size)
                    }
                    is Finished -> {
                        // The last samples are queued, not yet heard: wait for the buffer to drain
                        while (mine == token && out.playbackHeadPosition < written) Thread.sleep(15)
                        result = item.ok
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "playback failed", e)
            result = false
        }
        ticking.set(false)
        ticker.interrupt()
        ticker.join(200)
        if (mine == token) announceDue()
        synchronized(lock) { if (track === out) track = null }
        runCatching { out.stop() }
        out.release()
        Log.d(TAG, "end token=$mine current=$token result=$result")
        if (mine == token && result != null) onEnd(result)
    }

    fun cancel() {
        synchronized(lock) {
            token++
            // Releasing (not just pausing) unblocks a WRITE_BLOCKING call that is waiting for buffer space
            track?.let { runCatching { it.pause(); it.flush() }; it.release() }
            track = null
        }
    }

    /** Splits at the last punctuation (else comma, else space) inside each window; the first piece is short so sound starts sooner. */
    private fun chunks(text: String, firstMax: Int = 70, laterMax: Int = 180): List<String> {
        val out = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > (if (out.isEmpty()) firstMax else laterMax)) {
            val max = if (out.isEmpty()) firstMax else laterMax
            val window = rest.substring(0, max)
            val cut = listOf(".!?", ";:", ",", " ").firstNotNullOfOrNull { set ->
                window.indexOfLast { it in set }.takeIf { it >= max / 3 }
            } ?: (max - 1)
            out += rest.substring(0, cut + 1).trim()
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    fun release() {
        cancel()
        producer.execute { tts.release() }
        producer.shutdown()
        consumer.shutdown()
    }

    private fun newTrack(rate: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(min * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}
