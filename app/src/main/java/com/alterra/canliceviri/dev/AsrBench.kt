package com.alterra.canliceviri.dev

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.alterra.canliceviri.Languages
import com.alterra.canliceviri.asr.WhisperModel
import com.alterra.canliceviri.asr.WhisperNative
import com.alterra.canliceviri.asr.toFloatPcm
import com.alterra.canliceviri.audio.WavReader
import com.alterra.canliceviri.pipeline.TextFilters
import java.io.File
import java.util.Locale
import kotlin.math.min

/**
 * Geliştirici hız/doğruluk testi. files/test/manifest.tsv'deki her ses için:
 * dil tespiti (11 aday), dili bilinen yazıya dökme süresi ve karakter hata oranı (CER).
 * Tetikleme: adb shell am start -n com.alterra.canliceviri/.MainActivity --es bench "small,turbo;threads=4;ctx=auto,p4,768,full"
 */
object AsrBench {
    private const val TAG = "CanliCeviriBench"

    data class Spec(
        val models: List<String>,
        val threads: List<Int>,
        val ctxModes: List<String>,
        val beams: List<Int>,
        val flash: Boolean,
        /** Yalnızca bu dillerdeki test sesleri (boşsa hepsi). */
        val langs: Set<String>,
        /** Türkçe için nötr başlangıç istemi kullanılsın mı. */
        val prompt: Boolean,
        /** Takılmada sıcaklık artışı (0 = kapalı). */
        val fallbacks: List<Float>,
    )

    private const val TURKISH_PROMPT = WhisperModel.TURKISH_PROMPT

    fun parse(spec: String): Spec {
        val parts = spec.split(';').map { it.trim() }
        val models = parts.first().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val opts = parts.drop(1).mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        return Spec(
            models = models,
            threads = opts["threads"]?.split(',')?.mapNotNull { it.toIntOrNull() } ?: listOf(com.alterra.canliceviri.DeviceTuning.threads),
            ctxModes = opts["ctx"]?.split(',') ?: listOf("auto"),
            beams = opts["beam"]?.split(',')?.mapNotNull { it.toIntOrNull() } ?: listOf(1),
            flash = opts["flash"] == "1",
            langs = opts["diller"]?.split(',')?.map { it.trim() }?.toSet() ?: emptySet(),
            prompt = opts["istem"] == "1",
            fallbacks = opts["kurtarma"]?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: listOf(0f),
        )
    }

    /**
     * "full" = 30 sn, "auto" = ses + 1 sn, "p4" = ses + 4 sn (64'e yuvarlı), "q3" = ses + 3 sn (256'ya yuvarlı),
     * "768" = sabit (sesten kısa olmamak üzere).
     */
    private fun audioCtxFor(mode: String, samples: Int): Int = when {
        mode == "full" -> 0
        mode == "auto" -> WhisperModel.autoAudioCtx(samples)
        mode.startsWith("p") -> WhisperModel.autoAudioCtx(samples, padSeconds = mode.drop(1).toFloat())
        mode.startsWith("q") -> WhisperModel.autoAudioCtx(samples, padSeconds = mode.drop(1).toFloat(), multiple = 256)
        else -> maxOf(mode.toInt(), WhisperModel.autoAudioCtx(samples)).coerceAtMost(1500)
    }

    private fun modelFile(dir: File, name: String): File? = when (name) {
        "small" -> File(dir, "ggml-small-q8_0.bin")
        "turbo" -> File(dir, "ggml-large-v3-turbo-q5_0.bin")
        else -> File(dir, name).takeIf { it.exists() } ?: dir.listFiles()?.firstOrNull { it.name.contains(name) }
    }

    /** Sonuçları hem döndürür hem files/bench.txt'ye yazar. [progress] her satırda çağrılır. */
    fun run(context: Context, specText: String, progress: (String) -> Unit): String {
        val spec = parse(specText)
        val base = context.getExternalFilesDir(null) ?: return "dosya dizini yok"
        val modelsDir = File(base, "models")
        val testDir = File(base, "test")
        val manifest = File(testDir, "manifest.tsv")
        val out = StringBuilder()
        // Satır satır yazılıyor: test yarıda kalırsa (uygulama kapatılırsa) o ana kadarki sonuçlar kalsın.
        val file = File(base, "bench.txt").apply { writeText("") }
        fun emit(line: String) {
            out.appendLine(line)
            file.appendText(line + "\n")
            Log.i(TAG, line)
            progress(line)
        }

        emit("ASR testi · ${specText}")
        emit("CPU: ${WhisperNative.systemInfo().trim()}")
        if (!manifest.exists()) {
            emit("manifest.tsv yok: ${manifest.path}")
            return out.toString()
        }
        val tests = manifest.readLines().filter { it.isNotBlank() }.map { it.split('\t') }
            .filter { it.size >= 3 && (spec.langs.isEmpty() || it[1] in spec.langs) }
        val candidates = Languages.ALL.map { it.code }

        for (name in spec.models) {
            val file = modelFile(modelsDir, name)
            if (file == null || !file.exists()) {
                emit("model yok: $name")
                continue
            }
            val t0 = SystemClock.elapsedRealtime()
            val model = WhisperModel.load(file, flashAttn = spec.flash)
            if (model == null) {
                emit("model yüklenemedi: ${file.name}")
                continue
            }
            emit("")
            emit("== ${file.name} · yükleme ${SystemClock.elapsedRealtime() - t0} ms · flash=${spec.flash} istem=${spec.prompt}")
            model.use { m ->
                // Isınma: ilk çağrı bellek ayırdığı için ölçüme katılmıyor.
                m.transcribe(FloatArray(16_000), "en", spec.threads.first(), audioCtx = 128, beam = 1, maxTokens = 4)
                for (threads in spec.threads) for (ctxMode in spec.ctxModes) for (beam in spec.beams) for (fb in spec.fallbacks) {
                    emit("-- iş parçacığı $threads · bağlam $ctxMode · ışın $beam · kurtarma $fb · istem ${spec.prompt}")
                    var sumCer = 0.0
                    var sumRtf = 0.0
                    var lidOk = 0
                    for ((fileName, lang, reference) in tests) {
                        val wav = WavReader.read(File(testDir, fileName)) ?: continue
                        val pcm = wav.samples.toFloatPcm()
                        val seconds = pcm.size / 16_000f
                        // Uygulamadaki gibi: dil tespiti yazıya dökmeyle aynı bağlamda; kodlayıcı bir kez çalışıyor
                        // (dil süresi kodlamayı içeriyor, yazı süresi yalnızca çözme).
                        val audioCtx = audioCtxFor(ctxMode, pcm.size)
                        val guess = m.detectLanguage(pcm, candidates, threads, audioCtx)
                        val best = guess?.best
                        if (best?.first == lang) lidOk++
                        val prompt = if (spec.prompt && lang == "tr") TURKISH_PROMPT else null
                        val r = m.transcribeWithRetry(pcm, lang, threads, audioCtx, beam, prompt = prompt, fallback = fb)
                        // Uygulamadaki gibi: art arda tekrarlanan cümle silinir; CER ekranda görünecek metinle.
                        val text = TextFilters.collapseRepeats(r.text).let { if (lang == "bn") TextFilters.devanagariToBengali(it) else it }
                        val marks = buildString {
                            if (r.retried) append(" [döngü → ${WhisperModel.LOOP_RETRY_CTX} ile yeniden]")
                            else if (r.looped) append(" [döngü kesildi]")
                            if (lang != "bn" && text != r.text.trim()) append(" [tekrar silindi]")
                        }
                        val cer = cer(reference, text, lang)
                        sumCer += cer
                        // Dil tespiti + yazıya dökme (uygulamada tek mikrofonda ikisi de her cümlede çalışıyor).
                        val totalMs = (guess?.ms ?: 0f) + r.totalMs
                        sumRtf += totalMs / 1000f / seconds
                        emit(
                            String.format(
                                Locale.US,
                                "%-8s %4.1fs | dil %s %s %.2f (%4.0f ms) | yazı %5.0f ms (kod %4.0f, %3d belirteç × %4.1f ms) | toplam %5.0f ms | CER %4.1f%% | %s",
                                fileName, seconds, lang, if (best?.first == lang) "✓" else "✗→${best?.first}", best?.second ?: 0f, guess?.ms ?: 0f,
                                r.totalMs, r.encodeMs, r.tokens, r.decodeMsPerToken, totalMs, cer * 100,
                                text + marks,
                            )
                        )
                    }
                    emit(
                        String.format(
                            Locale.US, "Ortalama: CER %.1f%% · gerçek zaman oranı (dil + yazı) %.2f · dil tespiti %d/%d",
                            sumCer / tests.size * 100, sumRtf / tests.size, lidOk, tests.size,
                        )
                    )
                }
            }
        }
        emit("bitti")
        return out.toString()
    }

    /** Karakter hata oranı: noktalama ve büyük/küçük harf farkı sayılmaz. */
    fun cer(reference: String, hypothesis: String, lang: String): Double {
        val locale = Locale.forLanguageTag(lang)
        fun norm(s: String) = s.lowercase(locale).replace(Regex("[\\p{P}\\p{S}]"), " ").replace(Regex("\\s+"), " ").trim()
        val a = norm(reference).codePoints().toArray()
        val b = norm(hypothesis).codePoints().toArray()
        if (a.isEmpty()) return if (b.isEmpty()) 0.0 else 1.0
        var prev = IntArray(b.size + 1) { it }
        var cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            cur[0] = i
            for (j in 1..b.size) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.size].toDouble() / a.size
    }
}
