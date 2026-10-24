package com.readaloud.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/** A Kokoro speaker. [id] is the index sherpa-onnx expects, in the order the v1.0 model packs its voices. */
data class KokoroVoice(val id: Int, val name: String) {
    private val region = if (name[0] == 'a') "US" else "UK"
    private val gender = if (name[1] == 'f') "female" else "male"
    val label: String get() = "${name.substringAfter('_').replaceFirstChar { it.uppercase() }} · $region $gender"
}

/**
 * Kokoro v1.0 (fp32) running on-device through sherpa-onnx. The ~350 MB model is downloaded on demand into app storage
 * rather than shipped in the APK. fp32 is used because the int8 build ran about 1.7x slower than real time on a Fold
 * (about 1.3x with fp32). The v1.1 packages are the Chinese-focused model, so they are deliberately not used.
 */
object KokoroModel {
    val spec = ModelSpec(
        name = "kokoro-multi-lang-v1_0",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2",
        bytes = 349_906_910L,
        sha256 = "c5f7e2d2caf082bc1d20fb70334a61d99d20b484500aad32e7cf84c128ea3298",
    )

    /** The English speakers (ids 0-27 of the 53 in the v1.0 package; the rest are other languages). */
    val voices: List<KokoroVoice> = listOf(
        "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore", "af_nicole", "af_nova", "af_river",
        "af_sarah", "af_sky", "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael", "am_onyx",
        "am_puck", "am_santa", "bf_alice", "bf_emma", "bf_isabella", "bf_lily", "bm_daniel", "bm_fable", "bm_george",
        "bm_lewis",
    ).mapIndexed { i, n -> KokoroVoice(i, n) }

    val defaultVoice: KokoroVoice get() = voices.first { it.name == "af_heart" }

    /** Chinese text front-end data is dead weight for an English reader. */
    private fun skip(path: String) = path.startsWith("dict/") || path.contains("-zh") || path.endsWith(".fst")

    private fun dir(ctx: Context) = File(ctx.filesDir, "kokoro")
    private fun marker(ctx: Context) = File(dir(ctx), ".installed")

    fun installed(ctx: Context) = marker(ctx).exists()

    fun delete(ctx: Context) { dir(ctx).deleteRecursively() }

    /** Downloads (resuming if interrupted), checks and unpacks the package. [onStatus] names the phase, [onProgress] is 0..1 overall. */
    fun install(ctx: Context, isActive: () -> Boolean, onStatus: (String) -> Unit, onProgress: (Float) -> Unit) {
        val target = dir(ctx)
        ModelArchive.install(ctx, spec, target, ::skip, isActive, onStatus, onProgress)
        if (find(target) == null) {
            target.deleteRecursively()
            error("Downloaded package is missing model files")
        }
        marker(ctx).writeText("v1.0-fp32")
    }

    private class Files(val model: File, val voices: File, val tokens: File, val espeak: File, val lexicons: List<File>)

    /** Finds files by pattern so a slightly different package layout still loads. */
    private fun find(root: File): Files? {
        val all = root.walkTopDown().toList()
        val model = all.firstOrNull { it.isFile && it.name.endsWith(".onnx") } ?: return null
        val voices = all.firstOrNull { it.name == "voices.bin" } ?: return null
        val tokens = all.firstOrNull { it.name == "tokens.txt" } ?: return null
        val espeak = all.firstOrNull { it.isDirectory && it.name == "espeak-ng-data" } ?: return null
        val lexicons = all.filter { it.isFile && it.name.startsWith("lexicon") && it.name.endsWith("en.txt") }
        return Files(model, voices, tokens, espeak, lexicons)
    }

    /** Loads the model (a few seconds, so call off the main thread). */
    fun load(ctx: Context): KokoroSpeaker {
        val f = checkNotNull(find(dir(ctx))) { "Kokoro is not installed" }
        val kokoro = OfflineTtsKokoroModelConfig(
            model = f.model.path,
            voices = f.voices.path,
            tokens = f.tokens.path,
            dataDir = f.espeak.path,
            lexicon = f.lexicons.joinToString(",") { it.path },
            lang = "en-us",
        )
        val config = OfflineTtsConfig(model = OfflineTtsModelConfig(kokoro = kokoro, numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8), debug = false))
        return KokoroSpeaker(OfflineTts(null, config))
    }
}
