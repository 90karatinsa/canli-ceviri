package com.alterra.canliceviri.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.alterra.canliceviri.Lang
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Sistem seslendirme motoru (TTS). Ses doğrudan motorun hoparlörüne verilmiyor:
 * synthesizeToFile ile üretilen parçalar onAudioAvailable'dan alınıp kendi [PcmPlayer]'ımıza akıtılıyor.
 * Böylece her cümlenin çıkacağı cihazı (kulaklık / hoparlör) biz seçiyoruz.
 */
class TtsEngine(private val context: Context) {

    data class Engine(val packageName: String, val label: String)

    enum class State { READY, NEEDS_DOWNLOAD, NETWORK_ONLY, UNSUPPORTED, UNKNOWN }

    data class LangStatus(val lang: Lang, val state: State, val detail: String)

    data class SpeakResult(
        val ok: Boolean,
        val firstAudioMs: Long?,
        val totalMs: Long,
        val routed: AudioDeviceInfo?,
        val streamed: Boolean,
        val voice: String?,
        val error: String? = null,
    )

    private var tts: TextToSpeech? = null
    var enginePackage: String? = null; private set

    /** Okuma hızı (1,0 normal, 0,8 yavaş); her sentezden önce motora verilir. */
    @Volatile var speechRate: Float = 1f
    @Volatile var ready = false; private set
    private val pending = ConcurrentHashMap<String, Utterance>()

    fun engines(): List<Engine> = (tts?.engines ?: emptyList()).map { Engine(it.name, it.label) }

    val defaultEngine: String? get() = tts?.defaultEngine

    /** Motoru başlatır; [preferredPackage] null ise sistemin varsayılanı. */
    fun init(preferredPackage: String?, onReady: (Boolean) -> Unit) {
        shutdown()
        val listener = TextToSpeech.OnInitListener { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts?.setOnUtteranceProgressListener(progress)
            onReady(ready)
        }
        val t = if (preferredPackage != null) TextToSpeech(context, listener, preferredPackage) else TextToSpeech(context, listener)
        tts = t
        enginePackage = preferredPackage ?: t.defaultEngine
    }

    fun shutdown() {
        ready = false
        tts?.shutdown()
        tts = null
        pending.values.forEach { it.fail("motor kapatıldı") }
        pending.clear()
    }

    private fun allVoices(): Set<Voice> = try { tts?.voices } catch (_: Exception) { null } ?: emptySet()

    private fun voicesFor(all: Collection<Voice>, locale: Locale): List<Voice> {
        val lang = normLang(locale.language)
        return all.filter { normLang(it.locale.language) == lang }
    }

    private fun isInstalled(v: Voice) =
        v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true

    private fun isOffline(v: Voice) = !v.isNetworkConnectionRequired && isInstalled(v)

    /** Çevrimdışı çalışan en iyi ses; aynı ülke varyantı öncelikli. */
    fun pickVoice(locale: Locale, all: Collection<Voice> = allVoices()): Voice? {
        val offline = voicesFor(all, locale).filter(::isOffline)
        val sameCountry = offline.filter { it.locale.country.equals(locale.country, ignoreCase = true) }
        return sameCountry.ifEmpty { offline }.maxByOrNull { it.quality * 10 - it.latency }
    }

    /**
     * Seslendirmede kullanılacak ses: çevrimdışı olan, yoksa yüklü ama internet isteyen. Dilin hiç sesi yoksa null;
     * motor o zaman metni varsayılan (çoğunlukla İngilizce) sesle okuyor, anlaşılmaz oluyor.
     */
    private fun voiceFor(locale: Locale): Voice? {
        val all = allVoices()
        return pickVoice(locale, all) ?: voicesFor(all, locale).filter(::isInstalled).maxByOrNull { it.quality * 10 - it.latency }
    }

    /** Dillerin çevrimdışı seslendirme durumu. Ses listesi bir kez çekilir (Google motorunda yüzlerce ses var). */
    fun statuses(langs: List<Lang>): List<LangStatus> {
        val all = allVoices()
        return langs.map { status(it, all) }
    }

    private fun status(lang: Lang, all: Collection<Voice>): LangStatus {
        val t = tts
        if (t == null || !ready) return LangStatus(lang, State.UNKNOWN, "motor hazır değil")
        val avail = try { t.isLanguageAvailable(lang.ttsLocale) } catch (_: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
        val voices = voicesFor(all, lang.ttsLocale)
        val offline = voices.filter(::isOffline)
        val notInstalled = voices.filter { !isInstalled(it) }
        return when {
            offline.isNotEmpty() -> LangStatus(lang, State.READY, "${offline.size} çevrimdışı ses")
            avail == TextToSpeech.LANG_MISSING_DATA || notInstalled.isNotEmpty() ->
                LangStatus(lang, State.NEEDS_DOWNLOAD, "ses verisi indirilmeli")
            voices.isNotEmpty() && voices.all { it.isNetworkConnectionRequired } ->
                LangStatus(lang, State.NETWORK_ONLY, "yalnızca internetle")
            avail >= TextToSpeech.LANG_AVAILABLE -> LangStatus(lang, State.READY, "dil var (ses listesi yok)")
            avail == TextToSpeech.LANG_NOT_SUPPORTED -> LangStatus(lang, State.UNSUPPORTED, "desteklenmiyor")
            else -> LangStatus(lang, State.UNKNOWN, "durum $avail")
        }
    }

    /**
     * [text]'i [lang] ile seslendirip [device]'a çalar. [onDone] arka plan iş parçacığında,
     * ses bittikten sonra çağrılır.
     */
    fun speak(text: String, lang: Lang, device: AudioDeviceInfo?, onDone: (SpeakResult) -> Unit) {
        val t = tts
        if (t == null || !ready) {
            onDone(SpeakResult(false, null, 0, null, false, null, "seslendirme motoru hazır değil"))
            return
        }
        val voice = voiceFor(lang.ttsLocale)
        if (voice != null) {
            t.voice = voice
        } else {
            // Ses listesi vermeyen motorlar: dil destekleniyorsa dili seç; desteklenmiyorsa başka dilin sesiyle okuma.
            val avail = try { t.setLanguage(lang.ttsLocale) } catch (_: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
            if (avail < TextToSpeech.LANG_AVAILABLE) {
                onDone(SpeakResult(false, null, 0, null, false, null, "${lang.nameTr} sesi yüklü değil"))
                return
            }
        }
        t.setSpeechRate(speechRate)
        val id = UUID.randomUUID().toString()
        val file = File(context.cacheDir, "tts_$id.wav")
        val u = Utterance(file, device, voice?.name, onDone)
        pending[id] = u
        val r = t.synthesizeToFile(text, Bundle(), file, id)
        if (r != TextToSpeech.SUCCESS) {
            pending.remove(id)
            u.fail("seslendirme başlatılamadı ($r)")
        }
    }

    /** Dilin ses modelini belleğe alır: ilk okuma ~1 sn gecikmesin. Ses üretilir ama çalınmaz. */
    fun warmUp(lang: Lang) {
        val t = tts ?: return
        if (!ready) return
        val voice = voiceFor(lang.ttsLocale) ?: return
        t.voice = voice
        val id = UUID.randomUUID().toString()
        val file = File(context.cacheDir, "tts_$id.wav")
        val u = Utterance(file, null, voice.name, {}, play = false)
        pending[id] = u
        if (t.synthesizeToFile(lang.sample, Bundle(), file, id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            u.fail("ısınma başlatılamadı")
        }
    }

    /**
     * Sürmekte olan ve bekleyen tüm okumaları hemen keser (arama geldi, kullanıcı düğmeye bastı):
     * sentez durdurulur, çalma kuyruğu boşaltılır, ses cihazı bırakılır.
     */
    fun abort() {
        val items = pending.values.toList()
        pending.clear()
        items.forEach { it.abort() }
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {}

        override fun onBeginSynthesis(utteranceId: String, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
            pending[utteranceId]?.begin(sampleRateInHz, audioFormat, channelCount)
        }

        override fun onAudioAvailable(utteranceId: String, audio: ByteArray) {
            pending[utteranceId]?.audio(audio)
        }

        override fun onDone(utteranceId: String) {
            pending.remove(utteranceId)?.done()
        }

        @Deprecated("Eski imza; yenisi aşağıda")
        override fun onError(utteranceId: String) {
            pending.remove(utteranceId)?.fail("seslendirme hatası")
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            pending.remove(utteranceId)?.fail("seslendirme hatası (kod $errorCode)")
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            pending.remove(utteranceId)?.fail("durduruldu")
        }
    }

    /** Tek bir cümlenin sentezi ve çalınması ([play] false ise yalnızca sentez: ısınma). */
    private inner class Utterance(
        private val file: File,
        private val device: AudioDeviceInfo?,
        private val voiceName: String?,
        private val onDone: (SpeakResult) -> Unit,
        private val play: Boolean = true,
    ) {
        private val requestedAt = SystemClock.elapsedRealtime()
        private val queue = LinkedBlockingQueue<ShortArray>()
        @Volatile private var sampleRate = 0
        @Volatile private var format = AudioFormat.ENCODING_PCM_16BIT
        @Volatile private var channels = 1
        @Volatile private var firstAudioAt = 0L
        @Volatile private var finished = false
        @Volatile private var aborted = false
        private var carry: ByteArray? = null
        private var player: Thread? = null

        fun begin(rate: Int, audioFormat: Int, channelCount: Int) {
            sampleRate = rate
            format = audioFormat
            channels = channelCount.coerceAtLeast(1)
        }

        /** Hoparlörde Google TTS'in çıkışı kısık kalıyor; yumuşak sınırlayıcıyla yükseltiliyor. */
        private val boost = device?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

        fun audio(bytes: ByteArray) {
            if (!play || aborted || sampleRate <= 0) return
            if (firstAudioAt == 0L) firstAudioAt = SystemClock.elapsedRealtime()
            val pcm = toPcm16(bytes).let { if (boost) Dsp.boost(it, SPEAKER_GAIN) else it }
            if (player == null) {
                val rate = sampleRate
                player = thread(name = "tts-cal") { playLoop(rate) }
            }
            if (pcm.isNotEmpty()) queue.put(pcm)
        }

        private fun playLoop(rate: Int) {
            val p = PcmPlayer(rate, device)
            while (true) {
                val chunk = queue.take()
                if (chunk === END || aborted) break
                p.write(chunk)
            }
            if (aborted) {
                p.release()
                return
            }
            p.finish()
            complete(true, p.routedDevice, streamed = true, error = null)
        }

        /** Hemen kes: kuyruk boşalır, çalan ses cihazı bırakılır, sonuç "iptal" olarak döner. */
        fun abort() {
            aborted = true
            queue.clear()
            queue.put(END)
            complete(false, null, streamed = player != null, error = "iptal edildi")
        }

        fun done() {
            if (!play) {
                complete(true, null, streamed = false, error = null)
                return
            }
            if (player != null) {
                queue.put(END)
                return
            }
            // Motor parça parça ses vermediyse dosyadan oku ve çal.
            thread(name = "tts-dosya") {
                val pcm = if (aborted) null else WavReader.read(file)
                if (pcm == null || pcm.samples.isEmpty()) {
                    complete(false, null, streamed = false, error = "ses dosyası okunamadı")
                    return@thread
                }
                if (firstAudioAt == 0L) firstAudioAt = SystemClock.elapsedRealtime()
                val p = PcmPlayer(pcm.sampleRate, device)
                p.write(if (boost) Dsp.boost(pcm.samples, SPEAKER_GAIN) else pcm.samples)
                p.finish()
                complete(true, p.routedDevice, streamed = false, error = null)
            }
        }

        fun fail(message: String) {
            if (player != null) queue.put(END)
            complete(false, null, streamed = player != null, error = message)
        }

        @Synchronized
        private fun complete(ok: Boolean, routed: AudioDeviceInfo?, streamed: Boolean, error: String?) {
            if (finished) return
            finished = true
            file.delete()
            val now = SystemClock.elapsedRealtime()
            onDone(
                SpeakResult(
                    ok = ok,
                    firstAudioMs = if (firstAudioAt > 0) firstAudioAt - requestedAt else null,
                    totalMs = now - requestedAt,
                    routed = routed,
                    streamed = streamed,
                    voice = voiceName,
                    error = error,
                )
            )
        }

        /** Motorun verdiği baytları 16-bit mono PCM'e çevirir. */
        private fun toPcm16(input: ByteArray): ShortArray {
            val bytes = carry?.let { it + input } ?: input
            carry = null
            val bytesPerSample = when (format) {
                AudioFormat.ENCODING_PCM_8BIT -> 1
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                else -> 2
            }
            val frameBytes = bytesPerSample * channels
            val usable = bytes.size - bytes.size % frameBytes
            if (usable < bytes.size) carry = bytes.copyOfRange(usable, bytes.size)
            val frames = usable / frameBytes
            val bb = ByteBuffer.wrap(bytes, 0, usable).order(ByteOrder.LITTLE_ENDIAN)
            return ShortArray(frames) {
                var sum = 0f
                repeat(channels) {
                    sum += when (bytesPerSample) {
                        1 -> ((bb.get().toInt() and 0xff) - 128) / 128f
                        4 -> bb.getFloat()
                        else -> bb.getShort() / 32768f
                    }
                }
                ((sum / channels).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
        }
    }

    companion object {
        private val END = ShortArray(0)

        /** Hoparlör bacağı kazancı (~+5 dB); tepe yumuşak sınırlayıcıyla 0 dBFS'i aşmaz. */
        private const val SPEAKER_GAIN = 1.8f

        /** Android'in eski dil kodlarını ve Google TTS'in "cmn" kodunu ISO 639-1'e çevirir. */
        fun normLang(code: String): String = when (code.lowercase(Locale.ROOT)) {
            "in" -> "id"
            "iw" -> "he"
            "ji" -> "yi"
            "cmn" -> "zh"
            "fil" -> "tl"
            else -> code.lowercase(Locale.ROOT)
        }
    }
}
