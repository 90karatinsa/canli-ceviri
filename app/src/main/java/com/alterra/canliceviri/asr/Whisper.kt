package com.alterra.canliceviri.asr

import java.io.Closeable
import java.io.File
import kotlin.math.min

/** Yüklü bir Whisper modeli. Aynı modele aynı anda tek çağrı yapılır (yerel tarafta kilitli). */
class WhisperModel private constructor(private var handle: Long, val file: File) : Closeable {

    data class Result(
        val text: String,
        val lang: String,
        val totalMs: Float,
        val encodeMs: Float,
        val decodeMsPerToken: Float,
        val noSpeechProb: Float,
        val avgTokenP: Float,
        val tokens: Int,
        val ok: Boolean,
        /** Kod çözücü döngüye girdi; metin tekrar başlamadan kesildi. */
        val looped: Boolean = false,
        /** Döngü yüzünden uzun bağlamla yeniden çözüldü (süreler ikisinin toplamı). */
        val retried: Boolean = false,
    )

    data class LangGuess(val probs: Map<String, Float>, val ms: Float) {
        val best: Pair<String, Float>? get() = probs.maxByOrNull { it.value }?.toPair()
    }

    val name: String get() = file.name

    /**
     * [language] null ise Whisper dili kendisi seçer (tüm dillerden). [audioCtx] 0 ise 30 sn'lik tam bağlam;
     * kısa seste [autoAudioCtx] ile küçültmek kodlayıcıyı birkaç kat hızlandırıyor.
     */
    fun transcribe(
        pcm: FloatArray,
        language: String?,
        threads: Int = DEFAULT_THREADS,
        audioCtx: Int = autoAudioCtx(pcm.size),
        beam: Int = 1,
        maxTokens: Int = 160,
        prompt: String? = null,
        /** Takılan (tekrarlayan) çözümde sıcaklığı bu kadar artırıp yeniden dener; 0 kapalı. */
        fallback: Float = 0f,
    ): Result {
        check(handle != 0L) { "model kapalı" }
        val r = WhisperNative.transcribe(handle, pcm, language, threads, audioCtx, beam, maxTokens, prompt, fallback)
        return Result(
            text = r.text.toString(Charsets.UTF_8).trim(),
            lang = r.lang,
            totalMs = r.totalMs,
            encodeMs = r.encodeMs,
            decodeMsPerToken = r.decodeMs,
            noSpeechProb = r.noSpeechProb,
            avgTokenP = r.avgTokenP,
            tokens = r.tokens,
            ok = r.rc == 0,
            looped = r.loopAt >= 0,
        )
    }

    /**
     * Kısaltılmış bağlamla çözer; kod çözücü döngüye girerse (kısa bağlamın bilinen kusuru) metin tekrardan önce
     * kesilmiş gelir. Kesilen metin cümle sonuyla bitiyorsa (model cümleyi bitirip baştan başlamış) yeterli; cümle
     * yarıda kaldıysa daha uzun bağlamla bir kez daha çözülür.
     */
    fun transcribeWithRetry(
        pcm: FloatArray,
        language: String?,
        threads: Int = DEFAULT_THREADS,
        audioCtx: Int = autoAudioCtx(pcm.size),
        beam: Int = 1,
        maxTokens: Int = 160,
        prompt: String? = null,
        fallback: Float = 0f,
    ): Result {
        val first = transcribe(pcm, language, threads, audioCtx, beam, maxTokens, prompt, fallback)
        if (!first.ok || !first.looped || audioCtx == 0) return first
        if (first.text.trimEnd().lastOrNull()?.let { it in SENTENCE_END } == true) return first
        val retryCtx = if (audioCtx < LOOP_RETRY_CTX) LOOP_RETRY_CTX else 0
        val second = transcribe(pcm, language, threads, retryCtx, beam, maxTokens, prompt, fallback)
        if (!second.ok) return first
        return second.copy(totalMs = first.totalMs + second.totalMs, encodeMs = first.encodeMs + second.encodeMs, retried = true)
    }

    /**
     * Konuşmanın dili, yalnızca [candidates] arasından (ISO 639-1 kodları). Aynı ses hemen ardından aynı
     * [audioCtx] ile [transcribe] edilirse kodlayıcı ikinci kez çalışmaz; yazıya dökmede kullanılacak bağlamı ver.
     */
    fun detectLanguage(
        pcm: FloatArray,
        candidates: List<String>,
        threads: Int = DEFAULT_THREADS,
        audioCtx: Int = autoAudioCtx(pcm.size),
    ): LangGuess? {
        check(handle != 0L) { "model kapalı" }
        val out = WhisperNative.detectLanguage(handle, pcm, threads, candidates.toTypedArray(), audioCtx) ?: return null
        val raw = candidates.mapIndexed { i, c -> c to out[i + 1] }
        val sum = raw.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(1e-9f)
        return LangGuess(raw.associate { it.first to it.second / sum }, out[0])
    }

    override fun close() {
        if (handle != 0L) {
            WhisperNative.free(handle)
            handle = 0
        }
    }

    companion object {
        const val DEFAULT_THREADS = 4
        const val SAMPLE_RATE = 16_000

        /**
         * Türkçe için nötr başlangıç istemi: Whisper'ı Türkçe yazıma ve noktalamaya yönlendirir.
         * Test cümleleriyle kelime paylaşmamalı; yoksa ölçüm şişer.
         */
        const val TURKISH_PROMPT = "Merhaba, size bir şey sormak istiyorum. Yardımcı olabilir misiniz?"

        /** Döngüde yeniden denemenin bağlamı: 15 sn'lik pencerede döngü görülmedi. */
        const val LOOP_RETRY_CTX = 768

        private const val SENTENCE_END = ".?!。？！।؟"

        /**
         * Flash attention kapalı: whisper.cpp'nin bu yolu kodlayıcı ve çapraz dikkatte bağlamı 256'nın katına
         * dolduruyor ama dolguyu maskelemiyor. Kısaltılmış bağlam 256'nın katı değilse model önceki seslerden kalan
         * verilere de bakıyor; Türkçe testte hata oranı %3'ten %20-45'e çıktı. Kısa bağlamda hız farkı yok denecek kadar az.
         */
        fun load(file: File, flashAttn: Boolean = false): WhisperModel? {
            if (!file.exists()) return null
            val h = WhisperNative.init(file.absolutePath, flashAttn)
            return if (h == 0L) null else WhisperModel(h, file)
        }

        /** Saniyede 50 kodlayıcı adımı; sesin sonuna [padSeconds] pay bırakıp [multiple]'ın katına yuvarla. */
        fun autoAudioCtx(samples: Int, padSeconds: Float = 1f, multiple: Int = 64): Int {
            val seconds = samples.toFloat() / SAMPLE_RATE
            val ctx = ((seconds + padSeconds) * 50f).toInt()
            return min(1500, (ctx + multiple - 1) / multiple * multiple)
        }
    }
}

/** Akışlı Silero VAD: her 512 örneklik (32 ms) çerçeve için konuşma olasılığı. */
class SileroVad private constructor(private var handle: Long) : Closeable {

    fun probability(frame: FloatArray): Float {
        check(handle != 0L) { "VAD kapalı" }
        return WhisperNative.vadProcess(handle, frame)
    }

    fun reset() {
        if (handle != 0L) WhisperNative.vadReset(handle)
    }

    override fun close() {
        if (handle != 0L) {
            WhisperNative.vadFree(handle)
            handle = 0
        }
    }

    companion object {
        const val FRAME = 512

        fun load(file: File): SileroVad? {
            if (!file.exists()) return null
            val h = WhisperNative.vadInit(file.absolutePath, 1)
            return if (h == 0L) null else SileroVad(h)
        }
    }
}

/** 16-bit PCM → Whisper'ın beklediği -1..1 aralığında float. */
fun ShortArray.toFloatPcm(): FloatArray = FloatArray(size) { this[it] / 32768f }
