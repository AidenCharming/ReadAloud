package com.readaloud.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/** A Piper speaker; [id] is the speaker index inside the multi-speaker LibriTTS-R model. */
data class PiperVoice(val id: Int, val label: String)

/** Voice numbers as auditioned (1-based) and whether each sounds female or male, as judged by ear. */
private val voiceGenders = mapOf(1 to "female", 4 to "male", 6 to "male", 7 to "male", 8 to "male", 9 to "male", 11 to "female")

/**
 * Piper (VITS) running on-device through sherpa-onnx. It generates faster than real time on a phone, unlike Kokoro,
 * so it is the "fast" engine. The LibriTTS-R medium package holds 904 speakers; a handful are offered here.
 */
object PiperModel {
    val spec = ModelSpec(
        name = "vits-piper-en_US-libritts_r-medium",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-libritts_r-medium.tar.bz2",
        bytes = 82_038_311L,
        sha256 = "10dc268f3e371696d721486123e2705a9fc1faa113491979fde4d88dba1f1b1c",
    )

    /** The speakers picked by ear on the Fold; labels keep the 1-based numbers they were auditioned under. */
    val voices: List<PiperVoice> = voiceGenders.map { (n, gender) -> PiperVoice(n - 1, "Voice $n · $gender") }

    val defaultVoice: PiperVoice get() = voices.first()

    private fun dir(ctx: Context) = File(ctx.filesDir, "piper")
    private fun marker(ctx: Context) = File(dir(ctx), ".installed")

    fun installed(ctx: Context) = marker(ctx).exists()

    fun delete(ctx: Context) { dir(ctx).deleteRecursively() }

    fun install(ctx: Context, isActive: () -> Boolean, onStatus: (String) -> Unit, onProgress: (Float) -> Unit) {
        val target = dir(ctx)
        ModelArchive.install(ctx, spec, target, { it.endsWith(".json") || it.endsWith(".py") }, isActive, onStatus, onProgress)
        if (find(target) == null) {
            target.deleteRecursively()
            error("Downloaded package is missing model files")
        }
        marker(ctx).writeText("libritts_r-medium")
    }

    private class Files(val model: File, val tokens: File, val espeak: File)

    private fun find(root: File): Files? {
        val all = root.walkTopDown().toList()
        val model = all.firstOrNull { it.isFile && it.name.endsWith(".onnx") } ?: return null
        val tokens = all.firstOrNull { it.name == "tokens.txt" } ?: return null
        val espeak = all.firstOrNull { it.isDirectory && it.name == "espeak-ng-data" } ?: return null
        return Files(model, tokens, espeak)
    }

    /** Loads the model (a second or two, so call off the main thread). */
    fun load(ctx: Context): KokoroSpeaker {
        val f = checkNotNull(find(dir(ctx))) { "Piper is not installed" }
        val vits = OfflineTtsVitsModelConfig(f.model.path, "", f.tokens.path, f.espeak.path, "", 0.667f, 0.8f, 1.0f)
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(vits = vits, numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4), debug = false)
        )
        return KokoroSpeaker(OfflineTts(null, config))
    }
}
