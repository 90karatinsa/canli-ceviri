package com.alterra.canliceviri.pipeline

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import com.alterra.canliceviri.audio.WavWriter
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
        /** Bas-konuş: otomatik algılama kapalı, konuşan yalnızca düğme basılıyken kaydedilir. */
        val manual: Boolean = false,
        /** Seslendirme hızı (1,0 normal, 0,8 yavaş). */
        val speechRate: Float = 1f,
        /** Sorun bildirimi için son parçaların sesi saklansın (files/sorun/). */
        val attachAudio: Boolean = true,
        /** Her çeviri kaliteli (7B) modelle: yavaş ama güvenilir. Model yoksa hızlı model kullanılır. */
        val quality: Boolean = false,
    )

    /** İşçinin sırayla işlediği işler: ses parçası, yazılan metin, tekrar okuma, düzeltme. */
    private sealed class Task {
        class Audio(val seg: Segmenter.Segment) : Task()
        class Text(val text: String, val speaker: Speaker) : Task()
        class Respeak(val id: Long) : Task()
        class Edit(val id: Long, val text: String) : Task()
        /** Baloncuğu kaliteli modelle yeniden çevir ve oku. */
        class Requality(val id: Long) : Task()
    }

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status
    private val _utterances = MutableStateFlow<List<Utterance>>(emptyList())
    val utterances: StateFlow<List<Utterance>> = _utterances

    private val front = AudioFrontEnd(context)
    private var translator: HyMtTranslator? = null
    /**
     * Kaliteli (7B) çeviri modeli; ilk gerektiğinde yüklenir (~10 sn, ~5 GB bellek). İki model birlikte ~8 GB tutuyor;
     * kaliteli moddayken hızlı model bırakılır, hızlı modda 7B bir süre kullanılmayınca bırakılır.
     */
    private var translatorQuality: HyMtTranslator? = null
    private var qualityUnload: Job? = null
    private val tts = TtsEngine(context)
    private var ttsReady = false
    private var whisper: WhisperModel? = null
    private var vadUser: SileroVad? = null
    private var vadPartner: SileroVad? = null
    @Volatile private var segmenter: Segmenter? = null

    private var work = Channel<Task>(Channel.UNLIMITED)
    private var play = Channel<PlayItem>(Channel.UNLIMITED)
    private val jobs = ArrayList<Job>()
    private val worker = Dispatchers.Default.limitedParallelism(1)

    /** Başlat/durdur/duraklat tek tek işlensin: etkinlik ve hizmet aynı anda çağırabiliyor. */
    private val lifecycle = Mutex()

    /** Giriş çerçevesi sayacı; ekran kapalıyken mikrofonun aktığını günlükten görmek için. */
    private val framesIn = AtomicLong(0)

    /** Whisper için iş parçacığı sayısı; telefon ısınınca ([thermal]) yarıya iner. */
    @Volatile private var threadsNow = Settings().threads

    // Bas-konuş: düğme basılıyken çerçeveler burada birikir (VAD devre dışı).
    @Volatile private var manual: Speaker? = null
    private val manualUser = PcmBuffer()
    private val manualPartner = PcmBuffer()
    private var manualSince = 0L

    // Kesinti (arama, mikrofonun başka uygulamaya geçmesi): mikrofon kapalı, motor "çalışıyor" ama duraklamış.
    @Volatile private var pausedReason: String? = null
    private var speechAtPause = false
    private var normalSince = 0L
    private var lastResumeTry = 0L
    private var lastRestartAt = 0L
    private var deviceChangeAt = 0L

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Seslendirme sırasında ses odağı: müzik kısılır, odak gidince (zil) okuma kesilir.
    private var focusHeld = false
    private var focusRelease: Job? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            note("ses odağı alındı ($change): okuma kesildi")
            focusHeld = false
            tts.abort()
        }
    }
    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        .setOnAudioFocusChangeListener(focusListener, mainHandler)
        .build()

    // Kulaklık takıldı/çıktı: mikrofonlar yeniden açılır (çift mikrofon tek cihaza düşmesin).
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = onDevicesChanged(added, "takıldı")
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = onDevicesChanged(removed, "çıkarıldı")
    }

    /** Son olaylar (sorun bildirimi için): kararlar, kesintiler, hatalar. */
    private val events = ArrayDeque<String>()

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

    /** Kaliteli çeviri modeli indirilmiş mi (isteğe bağlı). */
    fun hasQualityModel(): Boolean = File(modelsDir, HyMtTranslator.QUALITY_MODEL_FILE).exists()

    fun setQuality(v: Boolean) {
        settings = settings.copy(quality = v)
        note("çeviri kalitesi: ${if (v) "kaliteli (7B)" else "hızlı (1,8B)"}")
        if (segmenter != null) scope.launch(worker) { applyModelPolicy() }
    }

    /** Kaliteli modeli gerekince yükler; dosya yoksa ya da açılamazsa null (hızlı model kullanılır). İşçide çağrılır. */
    private fun qualityTranslator(): HyMtTranslator? {
        qualityUnload?.cancel()
        translatorQuality?.let { return it }
        if (!hasQualityModel()) return null
        val t0 = System.currentTimeMillis()
        translatorQuality = HyMtTranslator.load(File(modelsDir, HyMtTranslator.QUALITY_MODEL_FILE), settings.threads)
        note("kaliteli çeviri modeli ${if (translatorQuality != null) "yüklendi" else "açılamadı"} (${System.currentTimeMillis() - t0} ms)")
        return translatorQuality
    }

    /** Hızlı model: kaliteli modda bırakılmış olabilir; gerekince yeniden yüklenir. İşçide çağrılır. */
    private fun fastTranslator(): HyMtTranslator? {
        translator?.let { return it }
        translator = HyMtTranslator.load(File(modelsDir, HyMtTranslator.MODEL_FILE), settings.threads)
        note("hızlı çeviri modeli ${if (translator != null) "yeniden yüklendi" else "açılamadı"}")
        return translator
    }

    /**
     * Bellek politikası (işçide): kaliteli modda 7B hazır tutulur, 1,8B bırakılır; hızlı modda 7B kullanılmadıkça
     * [QUALITY_IDLE_MS] sonra bırakılır (yazılan metin ve "kaliteli çevir" onu yeniden yükler).
     */
    private fun applyModelPolicy() {
        if (settings.quality && hasQualityModel()) {
            if (qualityTranslator() != null && translator != null) {
                translator?.close()
                translator = null
                note("hızlı çeviri modeli bırakıldı (kaliteli mod)")
            }
        } else {
            fastTranslator()
            scheduleQualityUnload()
        }
    }

    /** Sistem bellek daraldı dedi (onTrimMemory) ya da düşük bellek: hızlı moddaysa 7B hemen bırakılır. */
    fun trimMemory() {
        if (settings.quality || translatorQuality == null) return
        scope.launch(worker) {
            if (!settings.quality && translatorQuality != null) {
                translatorQuality?.close()
                translatorQuality = null
                note("kaliteli çeviri modeli bırakıldı (bellek daraldı)")
            }
        }
    }

    private fun scheduleQualityUnload() {
        qualityUnload?.cancel()
        if (translatorQuality == null || settings.quality) return
        qualityUnload = scope.launch(worker) {
            delay(QUALITY_IDLE_MS)
            if (!settings.quality) {
                translatorQuality?.close()
                translatorQuality = null
                note("kaliteli çeviri modeli bırakıldı (boşta)")
            }
        }
    }

    fun requality(id: Long) {
        work.trySend(Task.Requality(id))
    }

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
        note(message)
        _status.update { it.copy(error = message) }
    }

    /** Günlüğe ve olay halkasına yazar (sorun bildirimine girer). */
    fun note(line: String) {
        Log.i(TAG, line)
        synchronized(events) {
            events.addLast(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + line)
            while (events.size > EVENTS_MAX) events.removeFirst()
        }
    }

    fun recentEvents(): List<String> = synchronized(events) { events.toList() }

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
        tts.speechRate = settings.speechRate
        applyModelPolicy()

        val preferDual = settings.preferDual && settings.mode == Mode.CONVERSATION
        val mode = front.start(preferDual) { frames -> onFrames(frames) }
        if (mode == MicMode.NONE) {
            _status.update { it.copy(running = false, micMode = mode, error = front.info) }
            return
        }
        if (mode == MicMode.DUAL) vadUser = SileroVad.load(File(modelsDir, VAD_MODEL))
        segmenter = Segmenter(vadUser, vadPartner!!, silenceMs = settings.silenceMs)
        framesIn.set(0)
        pausedReason = null
        am.registerAudioDeviceCallback(deviceCallback, mainHandler)
        launchWorkers()
        _status.update {
            it.copy(
                running = true, micMode = mode, micInfo = front.info,
                partnerLang = partnerLang, partnerLangLocked = partnerLocked,
                error = if (!ttsReady) "Seslendirme motoru açılamadı; çeviriler yalnızca ekranda" else null,
            )
        }
        note("Motor başladı: ${settings.mode}, ${front.info}")
    }

    // ------------------------------------------------------------------ kesinti

    /** Arama ya da mikrofonun başka uygulamaya geçmesi: mikrofonlar kapanır, okumalar kesilir; motor çalışır görünür. */
    private suspend fun pause(reason: String) = lifecycle.withLock {
        if (segmenter == null || pausedReason != null) return@withLock
        pausedReason = reason
        speechAtPause = segmenter?.speaking == true || manual != null || _status.value.busy || !work.isEmpty
        manual = null
        front.stop()
        segmenter?.reset()
        drainPlays()
        tts.abort()
        normalSince = 0L
        _status.update { it.copy(paused = reason, hearingAnyone = false, hearing = null, recording = null, playing = false) }
        note("kesinti: $reason" + if (speechAtPause) " (konuşma ortasında)" else "")
    }

    /** Kesinti bitti: mikrofonlar yeniden açılır; konuşma yarıda kaldıysa karşı taraftan tekrar istenir. */
    private suspend fun resume() = lifecycle.withLock {
        val reason = pausedReason ?: return@withLock
        if (segmenter == null) return@withLock
        val mode = reopenCapture()
        if (mode == MicMode.NONE) {
            _status.update { it.copy(error = "Mikrofon geri alınamadı; Durdurup yeniden başlatın") }
            note("kesinti sonrası mikrofon açılamadı")
            return@withLock
        }
        pausedReason = null
        _status.update { it.copy(paused = null, micMode = mode, micInfo = front.info, error = null) }
        note("kesinti bitti ($reason): ${front.info}")
        if (speechAtPause) requestRepeat()
        speechAtPause = false
    }

    /** Mikrofonları kapatıp yeniden açar (3 deneme); susturulmuş geliyorsa başarısız sayılır. */
    private suspend fun reopenCapture(): MicMode {
        val preferDual = settings.preferDual && settings.mode == Mode.CONVERSATION
        repeat(3) { attempt ->
            front.stop()
            val mode = front.start(preferDual) { frames -> onFrames(frames) }
            if (mode != MicMode.NONE) {
                delay(300)
                if (!front.anySilenced()) {
                    rebuildSegmenter(mode)
                    return mode
                }
                note("mikrofon açıldı ama susturulmuş (deneme ${attempt + 1})")
            }
            delay(1000)
        }
        front.stop()
        return MicMode.NONE
    }

    private fun rebuildSegmenter(mode: MicMode) {
        if (mode == MicMode.DUAL && vadUser == null) vadUser = SileroVad.load(File(modelsDir, VAD_MODEL))
        val user = if (mode == MicMode.DUAL) vadUser else null
        segmenter = Segmenter(user, vadPartner!!, silenceMs = settings.silenceMs)
    }

    /** Kulaklık takıldı/çıktı ya da çift mikrofon tek cihaza düştü: dinlemeyi kesmeden mikrofonları yeniden aç. */
    private suspend fun restartCapture(reason: String) = lifecycle.withLock {
        if (segmenter == null || pausedReason != null) return@withLock
        lastRestartAt = System.currentTimeMillis()
        note("mikrofonlar yeniden açılıyor: $reason")
        val mode = reopenCapture()
        if (mode == MicMode.NONE) {
            pausedReason = "mikrofon açılamadı"
            _status.update { it.copy(paused = pausedReason, error = "Mikrofon açılamadı; yeniden deneniyor") }
            return@withLock
        }
        _status.update { it.copy(micMode = mode, micInfo = front.info) }
        note("mikrofon: ${front.info}")
    }

    private fun onDevicesChanged(devices: Array<out AudioDeviceInfo>, what: String) {
        val headset = devices.filter { AudioDevices.isHeadsetInput(it) || AudioDevices.isHeadsetOutput(it) }
        if (headset.isEmpty() || segmenter == null) return
        note("kulaklık $what: ${headset.joinToString { AudioDevices.describe(it) }}")
        val now = System.currentTimeMillis()
        if (now - deviceChangeAt < 1000) return
        deviceChangeAt = now
        scope.launch(Dispatchers.Default) {
            delay(800) // takma/çıkarma olayları art arda gelir; yönlendirme otursun
            restartCapture("kulaklık $what")
        }
    }

    /** Karşı taraftan tekrar isteme cümlesi: hoparlörden okunur, listede büyük yazıyla gösterilir. */
    fun requestRepeat() {
        val lang = Languages.FOREIGN.firstOrNull { it.code == partnerLang } ?: Languages.FOREIGN.first()
        val id = nextId++
        upsert(Utterance(id, Speaker.USER, UtteranceState.SPEAKING, 0, "tr", "(kesinti)", lang.code, lang.repeatRequest, note = "tekrar istendi"))
        if (ttsReady) enqueuePlay(id, Speaker.USER, lang.code, lang.repeatRequest, System.currentTimeMillis())
        else update(id) { it.copy(state = UtteranceState.DONE) }
    }

    /** Kuyruktaki okumaları at (kesinti): çalmakta olan `tts.abort()` ile kesilir. */
    private fun drainPlays() {
        while (play.tryReceive().isSuccess) { /* boşalt */ }
        pendingPlays.set(0)
        speakerPlaying = false
    }

    /** 2 Hz: arama modu, susturulmuş kayıt, yönlendirme; duraklamışsa sürdürme zamanı geldi mi. */
    private suspend fun checkInterruptions() {
        val mode = am.mode
        val inCall = mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
        val now = System.currentTimeMillis()
        val reason = pausedReason
        if (reason == null) {
            if (inCall) {
                pause("arama")
                return
            }
            if (front.anySilenced()) {
                pause("mikrofon başka bir uygulamada")
                return
            }
            if (front.mode == MicMode.DUAL && front.routedSame() && now - lastRestartAt > 5000) restartCapture("iki mikrofon aynı cihaza düştü")
            return
        }
        if (inCall) {
            normalSince = 0L
            return
        }
        if (normalSince == 0L) normalSince = now
        val wait = if (reason == "arama") 1200L else 2000L
        if (now - normalSince >= wait && now - lastResumeTry >= 2500) {
            lastResumeTry = now
            resume()
        }
    }

    // ------------------------------------------------------------------ ses odağı

    /** Okumadan önce geçici odak (müzik kısılır). Zil/arama sırasında verilmez: o zaman okunmaz. */
    private fun acquireFocus(): Boolean {
        focusRelease?.cancel()
        if (focusHeld) return true
        val r = am.requestAudioFocus(focusRequest)
        focusHeld = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!focusHeld) note("ses odağı verilmedi ($r)")
        return focusHeld
    }

    /** Kuyruk boşaldıysa kısa bir süre sonra odağı bırak (art arda cümlelerde müzik inip çıkmasın). */
    private fun releaseFocusLater() {
        focusRelease?.cancel()
        focusRelease = scope.launch(Dispatchers.Default) {
            delay(700)
            if (play.isEmpty && pendingPlays.get() == 0) releaseFocus()
        }
    }

    private fun releaseFocus() {
        if (!focusHeld) return
        focusHeld = false
        am.abandonAudioFocusRequest(focusRequest)
    }

    /** Simülasyon sesi: [isUser] çift mikrofonda sesin hangi kanala yüksek verileceğini belirler. */
    class SimClip(val pcm: FloatArray, val isUser: Boolean)

    /**
     * Geliştirici: mikrofon yerine verilen sesleri (16 kHz, -1..1) gerçek zamanlı hızda işler.
     * [dual] ise iki kanal üretilir: konuşanın kanalında ses olduğu gibi, diğerinde -14 dB kısık (karşı mikrofonun
     * duyduğu gibi). Her sesten sonra 1,6 sn sessizlik verilir ve çeviri seslendirilip bitene kadar beklenir.
     * [newSettings].manual ise her ses bir düğme basışı olarak verilir; [typed] metinler seslerden sonra yazılmış gibi işlenir.
     */
    suspend fun simulate(
        clips: List<SimClip>, newSettings: Settings, dual: Boolean, typed: List<Pair<Speaker, String>> = emptyList(),
    ) = withContext(Dispatchers.Default) {
        val ready = lifecycle.withLock {
            stopInternal()
            settings = newSettings.copy(preferDual = dual)
            threadsNow = settings.threads
            _status.update { it.copy(error = null) }
            if (!prepareModels()) return@withLock false
            tts.speechRate = settings.speechRate
            if (dual) vadUser = SileroVad.load(File(modelsDir, VAD_MODEL))
            segmenter = Segmenter(vadUser, vadPartner!!, silenceMs = settings.silenceMs)
            launchWorkers()
            val mode = if (dual) MicMode.DUAL else MicMode.SINGLE
            _status.update { it.copy(running = true, micMode = mode, micInfo = "Simülasyon: ${clips.size} test sesi (${if (dual) "çift" else "tek"} mikrofon)") }
            true
        }
        if (!ready) return@withContext
        suspend fun awaitIdle() {
            // Sıradaki ses, önceki çeviri seslendirilip bitmeden başlamasın (gerçekte de karşı taraf bekler).
            // Kaliteli model yükleme + çeviri dakikalar sürebiliyor (bellek baskısında); üst sınır 5 dk.
            var waited = 0
            while ((_status.value.busy || _status.value.playing || !work.isEmpty) && waited < 300_000) {
                delay(100)
                waited += 100
            }
            // Hoparlör sustuktan sonraki yankı payı bitmeden verilen ses kesilir.
            while (System.currentTimeMillis() < gateUntil + 100) delay(50)
        }
        for (clip in clips) {
            // Test sesleri farklı dillerde; her biri ayrı algılansın.
            if (!manualPartnerLang) unlockPartnerLang()
            val pcm = ShortArray(clip.pcm.size) { (clip.pcm[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
            if (settings.manual) {
                beginManual(if (clip.isUser) Speaker.USER else Speaker.PARTNER)
                feed(pcm, dual, clip.isUser)
                endManual()
            } else {
                feed(pcm, dual, clip.isUser)
                feed(ShortArray(WhisperModel.SAMPLE_RATE * 16 / 10), dual, clip.isUser)
            }
            awaitIdle()
        }
        for ((speaker, text) in typed) {
            if (!submitText(text, speaker)) Log.w(TAG, "metin işlenemedi ($speaker): $text")
            awaitIdle()
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
        jobs += scope.launch(worker) { for (j in work) handle(j) }
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
        am.unregisterAudioDeviceCallback(deviceCallback)
        segmenter = null
        manual = null
        pausedReason = null
        speakerPlaying = false
        pendingPlays.set(0)
        releaseFocus()
        _status.update { it.copy(running = false, paused = null, hearingAnyone = false, hearing = null, playing = false, busy = false, recording = null) }
    }

    fun release() {
        stopInternal()
        whisper?.close()
        whisper = null
        vadUser?.close()
        vadPartner?.close()
        translator?.close()
        translator = null
        translatorQuality?.close()
        translatorQuality = null
        tts.shutdown()
    }

    // ------------------------------------------------------------------ giriş

    /** "ses-girisi" iş parçacığında, her 32 ms'de bir. */
    private fun onFrames(f: AudioFrontEnd.Frames) {
        val seg = segmenter ?: return
        framesIn.incrementAndGet()
        manual?.let { who ->
            // Düğme basılı: ne VAD ne hoparlör kapısı; ham çerçeveler birikir.
            f.user?.let { manualUser.append(it) }
            manualPartner.append(f.partner)
            if (System.currentTimeMillis() - manualSince >= MANUAL_MAX_MS) endManual()
            return
        }
        if (settings.manual) return // bas-konuşta otomatik algılama kapalı
        if (speakerPlaying || System.currentTimeMillis() < gateUntil) {
            if (!gated) {
                seg.reset()
                gated = true
            }
            return
        }
        gated = false
        val s = seg.process(f) ?: return
        work.trySend(Task.Audio(s))
    }

    // ------------------------------------------------------------------ bas-konuş ve metin

    /** Bas-konuş: düğmeye basıldı. Hoparlörde okuma sürüyorsa kesilir (kullanıcı konuşmak istiyor). */
    fun beginManual(speaker: Speaker) {
        if (segmenter == null || manual != null) return
        if (speakerPlaying || _status.value.playing) tts.abort()
        manualUser.clear()
        manualPartner.clear()
        manualSince = System.currentTimeMillis()
        manual = speaker
        _status.update { it.copy(recording = speaker, recordingSince = manualSince) }
    }

    /** Bas-konuş: düğme bırakıldı; biriken ses konuşanı bilinen parça olarak işe gider. */
    fun endManual() {
        val who = manual ?: return
        manual = null
        val partner = manualPartner.take(0)
        val user = if (manualUser.size > 0) manualUser.take(0) else null
        _status.update { it.copy(recording = null) }
        val ms = partner.size * 1000 / WhisperModel.SAMPLE_RATE
        if (ms < MANUAL_MIN_MS) return
        work.trySend(Task.Audio(Segmenter.Segment(user, partner, ms, null, System.currentTimeMillis(), forcedSpeaker = who)))
    }

    /** Yazılan metin: Türkçe (kullanıcı) ya da karşı tarafın dilinde (dil seçili olmalı). */
    fun submitText(text: String, speaker: Speaker): Boolean {
        val t = text.trim()
        if (t.isEmpty() || segmenter == null) return false
        if (speaker == Speaker.PARTNER && partnerLang == null) return false
        return work.trySend(Task.Text(t, speaker)).isSuccess
    }

    fun respeak(id: Long) {
        work.trySend(Task.Respeak(id))
    }

    /** Konuşma biçimi çalışırken değiştirildi: düğmeyle ⇄ otomatik. */
    fun setManual(manual: Boolean) {
        settings = settings.copy(manual = manual)
        if (!manual) endManual()
        segmenter?.reset()
        note("konuşma biçimi: ${if (manual) "düğmeyle" else "otomatik"}")
    }

    fun setSpeechRate(rate: Float) {
        settings = settings.copy(speechRate = rate)
        tts.speechRate = rate
    }

    fun setAttachAudio(v: Boolean) {
        settings = settings.copy(attachAudio = v)
    }

    private val issueDir: File get() = File(context.getExternalFilesDir(null), "sorun").apply { mkdirs() }

    /** Sorun bildirimine eklenecek son parça kayıtları (en yeni önce). */
    fun recentSegmentFiles(): List<File> =
        issueDir.listFiles { f -> f.name.startsWith("parca-") }?.sortedByDescending { it.lastModified() }?.take(SEGMENT_FILES_MAX) ?: emptyList()

    /** Parçanın sesini sorun klasörüne yazar (konuşana göre kullanılan kanal; çift mikrofonda diğeri de). */
    private fun archiveSegment(id: Long, who: Speaker, lang: String, seg: Segmenter.Segment) {
        if (!settings.attachAudio) return
        try {
            val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
            val whoName = if (who == Speaker.USER) "siz" else "karsi"
            val used = if (who == Speaker.USER) seg.userPcm ?: seg.partnerPcm else seg.partnerPcm
            WavWriter(File(issueDir, "parca-$stamp-$id-$whoName-$lang.wav"), WhisperModel.SAMPLE_RATE).use { it.write(used) }
            val other = when {
                who == Speaker.USER && seg.userPcm != null -> seg.partnerPcm
                who == Speaker.PARTNER -> seg.userPcm
                else -> null
            }
            other?.let { o -> WavWriter(File(issueDir, "parca-$stamp-$id-$whoName-$lang-diger.wav"), WhisperModel.SAMPLE_RATE).use { it.write(o) } }
            issueDir.listFiles { f -> f.name.startsWith("parca-") }?.sortedByDescending { it.lastModified() }?.drop(SEGMENT_FILES_MAX)?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "parça kaydedilemedi", e)
        }
    }

    fun edit(id: Long, text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) work.trySend(Task.Edit(id, t))
    }

    /** Arayüz için seviye ve "kim konuşuyor" bilgisini 10 kez/sn günceller. */
    private suspend fun tickStatus() {
        var tick = 0
        while (true) {
            // Ekran kapalıyken de mikrofonun aktığı günlükten görülsün (5 sn'de bir).
            if (++tick % 50 == 0) Log.i(TAG, "giriş: ${framesIn.get()} çerçeve")
            if (tick % 5 == 0 && front.mode != MicMode.NONE || pausedReason != null) {
                try {
                    checkInterruptions()
                } catch (e: Exception) {
                    Log.e(TAG, "kesinti denetimi", e)
                }
            }
            val seg = segmenter
            val lu = front.levelUser
            val lp = front.levelPartner
            val hearing = if (seg?.speaking == true && front.mode == MicMode.DUAL) {
                if (lu - lp >= (userMeanDb + partnerMeanDb) / 2) Speaker.USER else Speaker.PARTNER
            } else null
            // Bellek daraldıysa (hızlı modda) boştaki 7B bırakılsın (10 sn'de bir).
            if (tick % 100 == 0 && translatorQuality != null && !settings.quality) {
                val mi = android.app.ActivityManager.MemoryInfo()
                (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
                if (mi.lowMemory || mi.availMem < LOW_MEMORY_BYTES) trimMemory()
            }
            // Hoparlör ses düzeyi uyarısı için (saniyede bir): kulaklık takılıyken sistem kulaklığın düzeyini gösterir.
            if (tick % 10 == 0) {
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
                val headset = AudioDevices.headsetOutput(context) != null
                _status.update { it.copy(speakerVolume = vol, headsetAttached = headset) }
            }
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

    private suspend fun handle(job: Task) {
        _status.update { it.copy(busy = true) }
        try {
            when (job) {
                is Task.Audio -> processAudio(job.seg)
                is Task.Text -> processText(job)
                is Task.Respeak -> respeakNow(job.id)
                is Task.Edit -> editNow(job)
                is Task.Requality -> requalityNow(job.id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "işleme hatası", e)
        } finally {
            _status.update { it.copy(busy = !work.isEmpty) }
        }
    }

    private suspend fun processAudio(seg: Segmenter.Segment) {
        val w = whisper ?: return
        val id = nextId++
        val audioMs = seg.partnerPcm.size * 1000L / WhisperModel.SAMPLE_RATE
        var speaker = when {
            seg.forcedSpeaker != null -> seg.forcedSpeaker
            settings.mode == Mode.LISTENING -> Speaker.PARTNER
            seg.meanDiffDb != null -> classify(seg.meanDiffDb)
            else -> null // tek mikrofon: dil söyleyecek
        }
        upsert(Utterance(id, speaker ?: Speaker.PARTNER, UtteranceState.TRANSCRIBING, audioMs))
        try {
            // Kullanıcıysa kulaklık mikrofonu (ağza yakın), değilse telefon mikrofonu.
            fun pcmFor(s: Speaker?) = (if (s == Speaker.USER) seg.userPcm ?: seg.partnerPcm else seg.partnerPcm).toFloatPcm()
            // Dil tespiti ve yazıya dökme aynı bağlamı kullanıyor: aynı sesse kodlayıcı bir kez çalışıyor.
            fun audioCtxFor(pcm: FloatArray) = if (settings.fullAudioCtx) 0 else WhisperModel.autoAudioCtx(pcm.size, settings.padSeconds)

            var lang: String
            var note: String? = null
            if (!manualPartnerLang && partnerLocked && System.currentTimeMillis() - lastPartnerAt > RELOCK_AFTER_MS) unlockPartnerLang()
            val forced = seg.forcedSpeaker != null
            if (speaker == Speaker.USER) {
                lang = "tr"
            } else if (partnerLocked && partnerLang != null && (speaker == Speaker.PARTNER || settings.mode == Mode.LISTENING)) {
                lang = partnerLang!!
            } else {
                // Dil tespiti: Türkçe çıkarsa konuşan kullanıcıdır (tek mikrofonda asıl yöntem bu).
                // Düğmeyle "karşı taraf" dendiyse Türkçe aday değil.
                val candidates = when {
                    forced -> Languages.FOREIGN.map { it.code }
                    settings.mode == Mode.LISTENING -> Languages.ALL.map { it.code }
                    partnerLocked && partnerLang != null -> listOf("tr", partnerLang!!)
                    else -> Languages.ALL.map { it.code }
                }
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
            archiveSegment(id, who, lang, seg)
            val pcm = pcmFor(who)
            // Düğmeyle alınan parçada VAD yok: içinde konuşma yoksa (sessiz oda) Whisper uydurur; atla.
            val raw = if (who == Speaker.USER) seg.userPcm ?: seg.partnerPcm else seg.partnerPcm
            if (forced && Dsp.powerToDb(Dsp.meanPower(raw)) < SILENT_DB) {
                upsert(Utterance(id, who, UtteranceState.DROPPED, audioMs, lang, null, asrMs = 0, note = "ses algılanmadı"))
                return
            }
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
            translateAndSpeak(id, who, lang, text, audioMs, asrMs, note, seg.endedAt, typed = false, quality = settings.quality)
        } catch (e: Exception) {
            Log.e(TAG, "işleme hatası", e)
            update(id) { it.copy(state = UtteranceState.ERROR, note = e.message) }
        }
    }

    private suspend fun processText(job: Task.Text) {
        val id = nextId++
        val lang = if (job.speaker == Speaker.USER) "tr" else (partnerLang ?: return)
        // Yazılan metinde bekleme kabul edilir: kaliteli model varsa onunla.
        translateAndSpeak(id, job.speaker, lang, job.text, 0, null, null, System.currentTimeMillis(), typed = true, quality = true)
    }

    private suspend fun requalityNow(id: Long) {
        val u = _utterances.value.firstOrNull { it.id == id } ?: return
        val text = u.text ?: return
        val lang = u.sourceLang ?: return
        translateAndSpeak(id, u.speaker, lang, text, u.audioMs, u.asrMs, null, System.currentTimeMillis(), typed = u.typed, quality = true)
    }

    private fun respeakNow(id: Long) {
        val u = _utterances.value.firstOrNull { it.id == id } ?: return
        val translation = u.translation ?: return
        val target = u.targetLang ?: return
        if (!ttsReady) return
        enqueuePlay(id, u.speaker, target, translation, System.currentTimeMillis())
    }

    private suspend fun editNow(job: Task.Edit) {
        val u = _utterances.value.firstOrNull { it.id == job.id } ?: return
        val lang = u.sourceLang ?: if (u.speaker == Speaker.USER) "tr" else (partnerLang ?: return)
        if (job.text == u.text) {
            respeakNow(job.id)
            return
        }
        translateAndSpeak(job.id, u.speaker, lang, job.text, u.audioMs, u.asrMs, "düzeltildi", System.currentTimeMillis(), typed = u.typed, quality = true)
    }

    /**
     * Metin hazır: çevir, listeye yaz, doğru cihaza okut. Ses parçası, yazılan metin, düzeltme ve kaliteli yeniden çeviri
     * bu yoldan geçer. [quality] istenip model yoksa hızlı model kullanılır.
     */
    private suspend fun translateAndSpeak(
        id: Long, who: Speaker, lang: String, text: String, audioMs: Long, asrMs: Long?, note: String?, speechEndedAt: Long,
        typed: Boolean, quality: Boolean,
    ) {
        val wantQuality = quality && hasQualityModel()
        val loading = wantQuality && translatorQuality == null
        upsert(
            Utterance(
                id, who, UtteranceState.TRANSLATING, audioMs, lang, text, asrMs = asrMs,
                note = if (loading) listOfNotNull(note, "kaliteli model yükleniyor…").joinToString(" · ") else note, typed = typed,
            )
        )
        val target = if (who == Speaker.USER) (partnerLang ?: "en") else "tr"
        if (lang == target) {
            upsert(Utterance(id, who, UtteranceState.DONE, audioMs, lang, text, target, text, asrMs, 0, note = note, typed = typed))
            return
        }
        val engine = (if (wantQuality) qualityTranslator() else null) ?: fastTranslator()
        val usedQuality = wantQuality && engine === translatorQuality && engine != null
        val t0 = System.currentTimeMillis()
        val mt = engine?.translate(text, target)
        if (usedQuality) scheduleQualityUnload()
        if (mt == null || !mt.ok || mt.text.isEmpty()) {
            upsert(Utterance(id, who, UtteranceState.ERROR, audioMs, lang, text, target, null, asrMs, note = "çeviri yapılamadı", typed = typed))
            return
        }
        val translation = mt.text
        val mtMs = System.currentTimeMillis() - t0
        val guessedTarget = if (who == Speaker.USER && partnerLang == null) "karşı tarafın dili belli değil, İngilizce kullanıldı" else null
        val qualityNote = if (usedQuality) "kaliteli çeviri" else null
        val targetNote = listOfNotNull(note, qualityNote, guessedTarget).joinToString(" · ").ifEmpty { null }
        upsert(
            Utterance(
                id, who, UtteranceState.SPEAKING, audioMs, lang, text, target, translation, asrMs, mtMs,
                note = targetNote, typed = typed, quality = usedQuality,
            )
        )
        if (ttsReady) enqueuePlay(id, who, target, translation, speechEndedAt)
        else update(id) { it.copy(state = UtteranceState.DONE) }
    }

    /** Kullanıcının çevirisi hoparlöre, karşı tarafınki kulaklığa (yoksa hoparlöre). */
    private fun enqueuePlay(id: Long, who: Speaker, target: String, text: String, speechEndedAt: Long) {
        val device = if (who == Speaker.USER) AudioDevices.speaker(context) else AudioDevices.headsetOutput(context) ?: AudioDevices.speaker(context)
        val targetLang = Languages.ALL.firstOrNull { it.code == target } ?: Languages.TURKISH
        pendingPlays.incrementAndGet()
        _status.update { it.copy(playing = true) }
        update(id) { it.copy(state = UtteranceState.SPEAKING) }
        if (!play.trySend(PlayItem(id, text, targetLang, device, speechEndedAt)).isSuccess) {
            // Motor bu arada durduruldu: baloncuk "okunuyor"da takılı kalmasın.
            pendingPlays.decrementAndGet()
            _status.update { it.copy(playing = false) }
            update(id) { it.copy(state = UtteranceState.DONE, note = listOfNotNull(it.note, "okunmadı: motor durdu").joinToString(" · ")) }
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
        note("konuşan: $s (fark ${"%.1f".format(diffDb)} dB, eşik ${"%.1f".format(threshold)})")
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
        if (!acquireFocus()) {
            val left = pendingPlays.decrementAndGet().coerceAtLeast(0)
            _status.update { it.copy(playing = left > 0) }
            update(item.utteranceId) { it.copy(state = UtteranceState.DONE, note = listOfNotNull(it.note, "okunamadı: arama sırasında").joinToString(" · ")) }
            return
        }
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
        if (left == 0) releaseFocusLater()
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
        note(
            "sonuç #${u.id} ${u.speaker} ${u.sourceLang}→${u.targetLang} ${u.state} ses=${u.audioMs}ms yazı=${u.asrMs}ms " +
                "çeviri=${u.mtMs}ms ilk-ses=${u.latencyMs}ms${if (u.typed) " yazılı" else ""} | ${u.text} | ${u.translation} | ${u.note ?: ""}",
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

        /** Bas-konuşta en kısa ve en uzun kayıt (Whisper penceresi 30 sn; pay bırakılıyor). */
        private const val MANUAL_MIN_MS = 250
        private const val MANUAL_MAX_MS = 20_000L
        private const val EVENTS_MAX = 200
        private const val SEGMENT_FILES_MAX = 20

        /** Hızlı modda 7B bu kadar süre kullanılmazsa bellekten bırakılır. */
        private const val QUALITY_IDLE_MS = 3 * 60 * 1000L

        /** Kullanılabilir bellek bunun altına inerse boştaki 7B bırakılır. */
        private const val LOW_MEMORY_BYTES = 1_500L * 1024 * 1024

        /** Bas-konuş parçasının ortalama gücü bunun altındaysa konuşma yok sayılır (dBFS). */
        private const val SILENT_DB = -55f
    }
}
