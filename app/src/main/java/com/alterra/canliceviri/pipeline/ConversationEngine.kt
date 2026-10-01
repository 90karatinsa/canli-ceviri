package com.alterra.canliceviri.pipeline

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.alterra.canliceviri.Lang
import com.alterra.canliceviri.Languages
import com.alterra.canliceviri.asr.SileroVad
import com.alterra.canliceviri.asr.WhisperModel
import com.alterra.canliceviri.asr.toFloatPcm
import com.alterra.canliceviri.audio.AudioDevices
import com.alterra.canliceviri.audio.Dsp
import com.alterra.canliceviri.audio.TtsEngine
import com.alterra.canliceviri.llm.HyMtTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Konuşma motoru: mikrofon → konuşma parçaları → kim konuştu → yazıya dökme → çeviri → seslendirme.
 * Türkçe çeviri kulaklığa, karşı tarafın dilindeki çeviri telefon hoparlörüne gider.
 * Hoparlör çalarken mikrofon girişi yok sayılır (uygulama kendi sesini tekrar çevirmesin).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationEngine(private val context: Context, private val scope: CoroutineScope) {

    data class Settings(
        val mode: Mode = Mode.CONVERSATION,
        val preferDual: Boolean = true,
        val silenceMs: Int = 600,
        val threads: Int = com.alterra.canliceviri.DeviceTuning.threads,
        val asrModel: String = DEFAULT_ASR_MODEL,
        /** Işın araması: Türkçe testte fayda etmedi (%3,9'a karşı %4,0) ama çözmeyi yarı yarıya yavaşlattı. */
        val beamTurkish: Int = 1,
        val beamForeign: Int = 1,
        val promptTurkish: Boolean = true,
        /** Kısaltılmış kodlayıcı bağlamı (hızlı) ya da 30 sn'lik tam bağlam (yavaş). */
        val fullAudioCtx: Boolean = false,
        /** Kısaltılmış bağlamda sesin sonuna bırakılan sessizlik payı (sn). */
        val padSeconds: Float = 1f,
        /** Güveni düşük çözümde sıcaklık artışı; 0 kapalı. Testlerde hiç devreye girmedi, gecikmeyi öngörülemez yapıyor. */
        val fallback: Float = 0f,
    )

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status
    private val _utterances = MutableStateFlow<List<Utterance>>(emptyList())
    val utterances: StateFlow<List<Utterance>> = _utterances

    private val front = AudioFrontEnd(context)
    private var translator: HyMtTranslator? = null
    private val tts = TtsEngine(context)
    private var ttsReady = false
    private var whisper: WhisperModel? = null
    private var vadUser: SileroVad? = null
    private var vadPartner: SileroVad? = null
    @Volatile private var segmenter: Segmenter? = null

    private var work = Channel<Segmenter.Segment>(Channel.UNLIMITED)
    private var play = Channel<PlayItem>(Channel.UNLIMITED)
    private val jobs = ArrayList<Job>()
    private val worker = Dispatchers.Default.limitedParallelism(1)

    /** Başlat/durdur/duraklat tek tek işlensin: etkinlik ve hizmet aynı anda çağırabiliyor. */
    private val lifecycle = Mutex()

    /** Giriş çerçevesi sayacı; ekran kapalıyken mikrofonun aktığını günlükten görmek için. */
    private val framesIn = AtomicLong(0)

    /** Whisper için iş parçacığı sayısı; telefon ısınınca ([thermal]) yarıya iner. */
    @Volatile private var threadsNow = Settings().threads

    /** Hoparlörden çeviri çalarken ve hemen sonrasında giriş yok sayılır. */
    @Volatile private var speakerPlaying = false
    @Volatile private var gateUntil = 0L
    private var gated = false

    /** Kuyrukta bekleyen ya da çalan seslendirmeler; "boşta" durumu bunlar bitince gelir. */
    private val pendingPlays = java.util.concurrent.atomic.AtomicInteger(0)

    private var settings = Settings()
    private var nextId = 1L

    init {
        if (Build.VERSION.SDK_INT >= 29) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.addThermalStatusListener(context.mainExecutor) { status ->
                val hot = status >= PowerManager.THERMAL_STATUS_SEVERE
                val next = if (hot) max(2, settings.threads / 2) else settings.threads
                if (next != threadsNow) {
                    threadsNow = next
                    Log.w(TAG, "ısı durumu $status: whisper iş parçacığı $next")
                }
            }
        }
    }

    // Çift mikrofonda kim konuştu: (kulaklık − telefon) seviye farkının iki kümesi, zamanla uyarlanıyor.
    private var userMeanDb = 12f
    private var partnerMeanDb = -6f

    // Karşı tarafın dili: elle seçilmiş ya da tespitle kilitlenmiş.
    @Volatile private var partnerLang: String? = null
    @Volatile private var partnerLocked = false
    private var manualPartnerLang = false
    private val langVotes = HashMap<String, Float>()
    /** Karşı taraf bu kadar süre konuşmazsa (yeni biri gelmiş olabilir) otomatik dil kilidi çözülür. */
    private var lastPartnerAt = 0L

    private class PlayItem(val utteranceId: Long, val text: String, val lang: Lang, val device: AudioDeviceInfo?, val speechEndedAt: Long)

    val modelsDir: File get() = File(context.getExternalFilesDir(null), "models")

    fun missingModels(asrModel: String = settings.asrModel): List<String> =
        listOf(asrModel, VAD_MODEL, HyMtTranslator.MODEL_FILE).filter { !File(modelsDir, it).exists() }

    fun setPartnerLanguage(code: String?) {
        manualPartnerLang = code != null
        partnerLang = code
        partnerLocked = code != null
        langVotes.clear()
        _status.update { it.copy(partnerLang = code, partnerLangLocked = code != null) }
    }

    /** Seslendirme motorunda hangi dillerin internetsiz sesi var (kurulum kartı için). */
    suspend fun voiceStatuses(): List<TtsEngine.LangStatus> = withContext(Dispatchers.Default) {
        if (ensureTts()) tts.statuses(Languages.ALL) else emptyList()
    }

    val ttsEnginePackage: String? get() = tts.enginePackage

    /** Google'ın ses motoru telefonda yok: 11 dilin internetsiz sesi yalnızca onda var. */
    val googleTtsMissing: Boolean get() = !isInstalled(GOOGLE_TTS)

    /** Motoru hazırlar; Google motoru sonradan kurulduysa ona geçer. */
    private suspend fun ensureTts(): Boolean {
        if (!ttsReady || (tts.enginePackage != GOOGLE_TTS && isInstalled(GOOGLE_TTS))) ttsReady = initTts()
        return ttsReady
    }

    private fun isInstalled(pkg: String) = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun clearConversation() {
        _utterances.value = emptyList()
    }

    /** Dışarıdan (ör. hizmet başlatılamadı) hata metni göster. */
    fun reportError(message: String) {
        Log.w(TAG, message)
        _status.update { it.copy(error = message) }
    }

    /** Engeller: model yükleme ve mikrofon açma sürer; Dispatchers.Default'ta çağrılıyor. Çalışıyorsa önce durdurur. */
    suspend fun start(newSettings: Settings) = withContext(Dispatchers.Default) {
        lifecycle.withLock {
            stopInternal()
            settings = newSettings
            threadsNow = settings.threads
            _status.update { it.copy(starting = true, error = null) }
            try {
                startInternal()
            } finally {
                _status.update { it.copy(starting = false) }
            }
        }
    }

    private suspend fun startInternal() {
        if (!prepareModels()) return

        val preferDual = settings.preferDual && settings.mode == Mode.CONVERSATION
        val mode = front.start(preferDual) { frames -> onFrames(frames) }
        if (mode == MicMode.NONE) {
            _status.update { it.copy(running = false, micMode = mode, error = front.info) }
            return
        }
        if (mode == MicMode.DUAL) vadUser = SileroVad.load(File(modelsDir, VAD_MODEL))
        segmenter = Segmenter(vadUser, vadPartner!!, silenceMs = settings.silenceMs)
        framesIn.set(0)
        launchWorkers()
        _status.update {
            it.copy(
                running = true, micMode = mode, micInfo = front.info,
                partnerLang = partnerLang, partnerLangLocked = partnerLocked,
                error = if (!ttsReady) "Seslendirme motoru açılamadı; çeviriler yalnızca ekranda" else null,
            )
        }
        Log.i(TAG, "Motor başladı: ${settings.mode}, ${front.info}")
    }

    /** Simülasyon sesi: [isUser] çift mikrofonda sesin hangi kanala yüksek verileceğini belirler. */
    class SimClip(val pcm: FloatArray, val isUser: Boolean)

    /**
     * Geliştirici: mikrofon yerine verilen sesleri (16 kHz, -1..1) gerçek zamanlı hızda işler.
     * [dual] ise iki kanal üretilir: konuşanın kanalında ses olduğu gibi, diğerinde -14 dB kısık (karşı mikrofonun
     * duyduğu gibi). Her sesten sonra 1,6 sn sessizlik verilir ve çeviri seslendirilip bitene kadar beklenir.
     */
    suspend fun simulate(clips: List<SimClip>, newSettings: Settings, dual: Boolean) = withContext(Dispatchers.Default) {
        val ready = lifecycle.withLock {
            stopInternal()
            settings = newSettings.copy(preferDual = dual)
            threadsNow = settings.threads
            _status.update { it.copy(error = null) }
            if (!prepareModels()) return@withLock false
            if (dual) vadUser = SileroVad.load(File(modelsDir, VAD_MODEL))
            segmenter = Segmenter(vadUser, vadPartner!!, silenceMs = settings.silenceMs)
            launchWorkers()
            val mode = if (dual) MicMode.DUAL else MicMode.SINGLE
            _status.update { it.copy(running = true, micMode = mode, micInfo = "Simülasyon: ${clips.size} test sesi (${if (dual) "çift" else "tek"} mikrofon)") }
            true
        }
        if (!ready) return@withContext
        for (clip in clips) {
            // Test sesleri farklı dillerde; her biri ayrı algılansın.
            if (!manualPartnerLang) unlockPartnerLang()
            feed(ShortArray(clip.pcm.size) { (clip.pcm[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }, dual, clip.isUser)
            feed(ShortArray(WhisperModel.SAMPLE_RATE * 16 / 10), dual, clip.isUser)
            // Sıradaki ses, önceki çeviri seslendirilip bitmeden başlamasın (gerçekte de karşı taraf bekler).
            var waited = 0
            while ((_status.value.busy || _status.value.playing || !work.isEmpty) && waited < 120_000) {
                delay(100)
                waited += 100
            }
            // Hoparlör sustuktan sonraki yankı payı bitmeden verilen ses kesilir.
            while (System.currentTimeMillis() < gateUntil + 100) delay(50)
        }
        lifecycle.withLock { stopInternal() }
    }

    private suspend fun feed(pcm: ShortArray, dual: Boolean, isUser: Boolean) {
        var off = 0
        val noise = java.util.Random(7)
        while (off + Segmenter.FRAME <= pcm.size) {
            val loud = pcm.copyOfRange(off, off + Segmenter.FRAME)
            val frames = if (!dual) {
                AudioFrontEnd.Frames(null, loud, -120f, Dsp.powerToDb(Dsp.meanPower(loud)))
            } else {
                // Uzaktaki mikrofon: -14 dB ve biraz oda gürültüsü.
                val quiet = ShortArray(loud.size) { (loud[it] * 0.2f + noise.nextGaussian() * 30).toInt().coerceIn(-32768, 32767).toShort() }
                val user = if (isUser) loud else quiet
                val partner = if (isUser) quiet else loud
                AudioFrontEnd.Frames(user, partner, Dsp.powerToDb(Dsp.meanPower(user)), Dsp.powerToDb(Dsp.meanPower(partner)))
            }
            onFrames(frames)
            off += Segmenter.FRAME
            delay(Segmenter.FRAME_MS.toLong())
        }
    }

    /** Whisper, ses algılama ve seslendirme motorunu hazırlar. */
    private suspend fun prepareModels(): Boolean {
        val missing = missingModels()
        if (missing.isNotEmpty()) {
            _status.update { it.copy(running = false, error = "Model dosyası eksik: ${missing.joinToString()}") }
            return false
        }
        if (whisper?.name != settings.asrModel) {
            whisper?.close()
            whisper = WhisperModel.load(File(modelsDir, settings.asrModel))
            warmUp { whisper?.transcribe(FloatArray(WhisperModel.SAMPLE_RATE / 2), "tr", settings.threads, audioCtx = 256, maxTokens = 4) }
        }
        if (whisper == null) {
            _status.update { it.copy(error = "Konuşma tanıma modeli açılamadı") }
            return false
        }
        if (translator == null) {
            translator = HyMtTranslator.load(File(modelsDir, HyMtTranslator.MODEL_FILE), settings.threads)
            if (translator == null) {
                _status.update { it.copy(error = "Çeviri modeli açılamadı") }
                return false
            }
            warmUp { translator?.translate("Merhaba", "en") }
        }
        // Türkçe ve karşı tarafın dilinin (bilinmiyorsa İngilizce) sesi belleğe alınsın; ilk çeviri gecikmesin.
        if (ensureTts()) {
            tts.warmUp(Languages.TURKISH)
            tts.warmUp(Languages.FOREIGN.firstOrNull { it.code == partnerLang } ?: Languages.FOREIGN.first())
        }
        vadPartner?.close()
        vadUser?.close()
        vadUser = null
        segmenter = null
        vadPartner = SileroVad.load(File(modelsDir, VAD_MODEL))
        if (vadPartner == null) {
            _status.update { it.copy(error = "Ses algılama modeli açılamadı") }
            return false
        }
        return true
    }

    /** İlk çağrı bellek ayırıp yavaş çalışıyor (ilk cümlede ~1,5 sn fazla); model açılınca bir kez boşuna çalıştır. */
    private inline fun warmUp(call: () -> Unit) {
        val t0 = System.currentTimeMillis()
        call()
        Log.i(TAG, "ısınma ${System.currentTimeMillis() - t0} ms")
    }

    private fun launchWorkers() {
        work = Channel(Channel.UNLIMITED)
        play = Channel(Channel.UNLIMITED)
        jobs += scope.launch(worker) { for (s in work) process(s) }
        jobs += scope.launch(Dispatchers.Default) { for (p in play) speak(p) }
        jobs += scope.launch(Dispatchers.Default) { tickStatus() }
    }

    fun stop() {
        scope.launch(Dispatchers.Default) { lifecycle.withLock { stopInternal() } }
    }

    private fun stopInternal() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        work.close()
        play.close()
        front.stop()
        segmenter = null
        speakerPlaying = false
        pendingPlays.set(0)
        _status.update { it.copy(running = false, hearingAnyone = false, hearing = null, playing = false, busy = false) }
    }

    fun release() {
        stopInternal()
        whisper?.close()
        whisper = null
        vadUser?.close()
        vadPartner?.close()
        translator?.close()
        translator = null
        tts.shutdown()
    }

    // ------------------------------------------------------------------ giriş

    /** "ses-girisi" iş parçacığında, her 32 ms'de bir. */
    private fun onFrames(f: AudioFrontEnd.Frames) {
        val seg = segmenter ?: return
        framesIn.incrementAndGet()
        if (speakerPlaying || System.currentTimeMillis() < gateUntil) {
            if (!gated) {
                seg.reset()
                gated = true
            }
            return
        }
        gated = false
        val s = seg.process(f) ?: return
        work.trySend(s)
    }

    /** Arayüz için seviye ve "kim konuşuyor" bilgisini 10 kez/sn günceller. */
    private suspend fun tickStatus() {
        var tick = 0
        while (true) {
            // Ekran kapalıyken de mikrofonun aktığı günlükten görülsün (5 sn'de bir).
            if (++tick % 50 == 0) Log.i(TAG, "giriş: ${framesIn.get()} çerçeve")
            val seg = segmenter
            val lu = front.levelUser
            val lp = front.levelPartner
            val hearing = if (seg?.speaking == true && front.mode == MicMode.DUAL) {
                if (lu - lp >= (userMeanDb + partnerMeanDb) / 2) Speaker.USER else Speaker.PARTNER
            } else null
            _status.update {
                it.copy(
                    levelUser = lu, levelPartner = lp,
                    hearingAnyone = seg?.speaking == true, hearing = hearing,
                    partnerLang = partnerLang, partnerLangLocked = partnerLocked,
                )
            }
            delay(100)
        }
    }

    // ------------------------------------------------------------------ işleme

    private suspend fun process(seg: Segmenter.Segment) {
        val w = whisper ?: return
        val id = nextId++
        val audioMs = seg.partnerPcm.size * 1000L / WhisperModel.SAMPLE_RATE
        var speaker = when {
            settings.mode == Mode.LISTENING -> Speaker.PARTNER
            seg.meanDiffDb != null -> classify(seg.meanDiffDb)
            else -> null // tek mikrofon: dil söyleyecek
        }
        upsert(Utterance(id, speaker ?: Speaker.PARTNER, UtteranceState.TRANSCRIBING, audioMs))
        _status.update { it.copy(busy = true) }
        try {
            // Kullanıcıysa kulaklık mikrofonu (ağza yakın), değilse telefon mikrofonu.
            fun pcmFor(s: Speaker?) = (if (s == Speaker.USER) seg.userPcm ?: seg.partnerPcm else seg.partnerPcm).toFloatPcm()
            // Dil tespiti ve yazıya dökme aynı bağlamı kullanıyor: aynı sesse kodlayıcı bir kez çalışıyor.
            fun audioCtxFor(pcm: FloatArray) = if (settings.fullAudioCtx) 0 else WhisperModel.autoAudioCtx(pcm.size, settings.padSeconds)

            var lang: String
            var note: String? = null
            if (!manualPartnerLang && partnerLocked && System.currentTimeMillis() - lastPartnerAt > RELOCK_AFTER_MS) unlockPartnerLang()
            if (speaker == Speaker.USER) {
                lang = "tr"
            } else if (partnerLocked && partnerLang != null && (speaker == Speaker.PARTNER || settings.mode == Mode.LISTENING)) {
                lang = partnerLang!!
            } else {
                // Dil tespiti: Türkçe çıkarsa konuşan kullanıcıdır (tek mikrofonda asıl yöntem bu).
                val candidates = if (settings.mode == Mode.LISTENING) Languages.ALL.map { it.code }
                else if (partnerLocked && partnerLang != null) listOf("tr", partnerLang!!)
                else Languages.ALL.map { it.code }
                val lidPcm = pcmFor(speaker)
                val guess = w.detectLanguage(lidPcm, candidates, threadsNow, audioCtx = audioCtxFor(lidPcm))
                val best = guess?.best
                if (best == null) {
                    lang = partnerLang ?: "en"
                } else if (best.first == "tr" && settings.mode == Mode.CONVERSATION && (speaker == null || best.second > 0.8f)) {
                    speaker = Speaker.USER
                    lang = "tr"
                } else {
                    val foreign = guess.probs.filterKeys { it != "tr" }.maxByOrNull { it.value }
                    lang = foreign?.key ?: "en"
                    speaker = Speaker.PARTNER
                    if (best.first == "tr" && settings.mode == Mode.LISTENING) lang = "tr"
                    if (!manualPartnerLang && lang != "tr") vote(lang, foreign?.value ?: 0f)
                    note = "dil: ${lang} ${(100 * (foreign?.value ?: 0f)).toInt()}% (${guess.ms.toInt()} ms)"
                }
            }
            val who = speaker ?: Speaker.PARTNER
            if (who == Speaker.PARTNER) lastPartnerAt = System.currentTimeMillis()
            val pcm = pcmFor(who)
            val maxTokens = max(24, (audioMs / 1000f * 15).toInt() + 10)
            val turkish = lang == "tr"
            val r = w.transcribeWithRetry(
                pcm, lang, threadsNow,
                audioCtx = audioCtxFor(pcm),
                beam = if (turkish) settings.beamTurkish else settings.beamForeign,
                maxTokens = maxTokens,
                prompt = if (turkish && settings.promptTurkish) WhisperModel.TURKISH_PROMPT else null,
                fallback = settings.fallback,
            )
            val asrMs = System.currentTimeMillis() - seg.endedAt
            if (r.looped || r.retried) {
                val loop = if (r.retried) "döngü: uzun bağlamla yeniden çözüldü" else "döngü kesildi"
                note = if (note == null) loop else "$note · $loop"
            }
            val text = TextFilters.collapseRepeats(r.text).let { if (lang == "bn") TextFilters.devanagariToBengali(it) else it }
            if (!r.ok || TextFilters.isHallucination(text, lang, r.noSpeechProb, r.avgTokenP)) {
                upsert(Utterance(id, who, UtteranceState.DROPPED, audioMs, lang, text, asrMs = asrMs, note = "gürültü sayıldı"))
                return
            }
            upsert(Utterance(id, who, UtteranceState.TRANSLATING, audioMs, lang, text, asrMs = asrMs, note = note))

            val target = if (who == Speaker.USER) (partnerLang ?: "en") else "tr"
            if (lang == target) {
                upsert(Utterance(id, who, UtteranceState.DONE, audioMs, lang, text, target, text, asrMs, 0, note = note))
                return
            }
            val t0 = System.currentTimeMillis()
            val mt = translator?.translate(text, target)
            if (mt == null || !mt.ok || mt.text.isEmpty()) {
                upsert(Utterance(id, who, UtteranceState.ERROR, audioMs, lang, text, target, null, asrMs, note = "çeviri yapılamadı"))
                return
            }
            val translation = mt.text
            val mtMs = System.currentTimeMillis() - t0
            val guessedTarget = if (who == Speaker.USER && partnerLang == null) "karşı tarafın dili belli değil, İngilizce kullanıldı" else null
            val targetNote = listOfNotNull(note, guessedTarget).joinToString(" · ").ifEmpty { null }
            upsert(Utterance(id, who, UtteranceState.SPEAKING, audioMs, lang, text, target, translation, asrMs, mtMs, note = targetNote))

            if (ttsReady) {
                val device = if (who == Speaker.USER) AudioDevices.speaker(context) else AudioDevices.headsetOutput(context) ?: AudioDevices.speaker(context)
                val targetLang = Languages.ALL.firstOrNull { it.code == target } ?: Languages.TURKISH
                pendingPlays.incrementAndGet()
                _status.update { it.copy(playing = true) }
                play.trySend(PlayItem(id, translation, targetLang, device, seg.endedAt))
            } else {
                update(id) { it.copy(state = UtteranceState.DONE) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "işleme hatası", e)
            update(id) { it.copy(state = UtteranceState.ERROR, note = e.message) }
        } finally {
            _status.update { it.copy(busy = !work.isEmpty) }
        }
    }

    /** Seviye farkına göre kim konuştu; iki kümenin ortalamasını yavaşça günceller. */
    private fun classify(diffDb: Float): Speaker {
        val threshold = (userMeanDb + partnerMeanDb) / 2
        val s = if (diffDb >= threshold) Speaker.USER else Speaker.PARTNER
        if (s == Speaker.USER) userMeanDb = 0.8f * userMeanDb + 0.2f * diffDb else partnerMeanDb = 0.8f * partnerMeanDb + 0.2f * diffDb
        // Kümeler birbirine çok yaklaşırsa ayrımı koru.
        if (userMeanDb - partnerMeanDb < 6f) {
            val mid = (userMeanDb + partnerMeanDb) / 2
            userMeanDb = mid + 3f
            partnerMeanDb = mid - 3f
        }
        Log.i(TAG, "konuşan: $s (fark ${"%.1f".format(diffDb)} dB, eşik ${"%.1f".format(threshold)})")
        return s
    }

    private fun unlockPartnerLang() {
        partnerLocked = false
        partnerLang = null
        langVotes.clear()
        _status.update { it.copy(partnerLang = null, partnerLangLocked = false) }
    }

    /** Karşı tarafın dili: tek tespitte %85 ya da aynı dile iki oy çıkınca kilitlenir. */
    private fun vote(lang: String, prob: Float) {
        if (partnerLocked) return
        langVotes[lang] = (langVotes[lang] ?: 0f) + prob
        if (partnerLang == null || (langVotes[lang] ?: 0f) > (langVotes[partnerLang] ?: 0f)) partnerLang = lang
        if (prob >= 0.85f || (langVotes[lang] ?: 0f) >= 1.5f) partnerLocked = true
        _status.update { it.copy(partnerLang = partnerLang, partnerLangLocked = partnerLocked) }
    }

    // ------------------------------------------------------------------ seslendirme

    private suspend fun speak(item: PlayItem) {
        val toSpeaker = item.device?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        if (toSpeaker) speakerPlaying = true
        _status.update { it.copy(playing = true) }
        val startedAt = System.currentTimeMillis()
        val r = suspendCancellableCoroutine { cont ->
            tts.speak(item.text, item.lang, item.device) { res -> if (cont.isActive) cont.resume(res) }
        }
        if (toSpeaker) {
            gateUntil = System.currentTimeMillis() + 350
            speakerPlaying = false
        }
        val left = pendingPlays.decrementAndGet().coerceAtLeast(0)
        _status.update { it.copy(playing = left > 0) }
        val latency = r.firstAudioMs?.let { startedAt + it - item.speechEndedAt }
        Log.i(TAG, "seslendirme #${item.utteranceId} ${item.lang.code} ses=${r.voice} ilk-ses=${r.firstAudioMs} ms ${r.error ?: ""}")
        // Çeviri yapıldı; ses çıkmadıysa bile ekranda duruyor, yalnızca neden okunmadığı yazılıyor.
        update(item.utteranceId) {
            it.copy(
                state = UtteranceState.DONE,
                latencyMs = latency,
                note = if (r.ok) it.note else listOfNotNull(it.note, "okunamadı: ${r.error}").joinToString(" · "),
            )
        }
    }

    private suspend fun initTts(): Boolean = suspendCancellableCoroutine { cont ->
        val preferred = GOOGLE_TTS.takeIf { isInstalled(it) }
        tts.init(preferred) { ok -> if (cont.isActive) cont.resume(ok) }
    }

    // ------------------------------------------------------------------ liste

    private fun logResult(u: Utterance) {
        Log.i(
            TAG,
            "sonuç #${u.id} ${u.speaker} ${u.sourceLang}→${u.targetLang} ${u.state} ses=${u.audioMs}ms yazı=${u.asrMs}ms " +
                "çeviri=${u.mtMs}ms ilk-ses=${u.latencyMs}ms | ${u.text} | ${u.translation} | ${u.note ?: ""}",
        )
    }

    private fun upsert(u: Utterance) {
        if (u.state == UtteranceState.DONE || u.state == UtteranceState.DROPPED || u.state == UtteranceState.ERROR) logResult(u)
        _utterances.update { list ->
            val i = list.indexOfFirst { it.id == u.id }
            val next = if (i >= 0) list.toMutableList().also { it[i] = u } else list + u
            next.takeLast(60)
        }
    }

    private fun update(id: Long, change: (Utterance) -> Utterance) {
        _utterances.update { list -> list.map { if (it.id == id) change(it) else it } }
        _utterances.value.firstOrNull { it.id == id }?.let { u ->
            if (u.state == UtteranceState.DONE || u.state == UtteranceState.ERROR) logResult(u)
        }
    }

    companion object {
        private const val TAG = "CanliCeviri"
        const val DEFAULT_ASR_MODEL = "ggml-small-q8_0.bin"
        const val VAD_MODEL = "ggml-silero-v6.2.0.bin"
        const val GOOGLE_TTS = "com.google.android.tts"
        private const val RELOCK_AFTER_MS = 90_000L
    }
}
