package com.alterra.canliceviri.diag

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alterra.canliceviri.BuildConfig
import com.alterra.canliceviri.Lang
import com.alterra.canliceviri.Languages
import com.alterra.canliceviri.audio.AudioDevices
import com.alterra.canliceviri.audio.Dsp
import com.alterra.canliceviri.audio.MicCapture
import com.alterra.canliceviri.audio.PcmPlayer
import com.alterra.canliceviri.audio.TtsEngine
import com.alterra.canliceviri.dev.AsrBench
import com.alterra.canliceviri.dev.MtBench
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max

enum class Kind { OK, WARN, FAIL }

data class Verdict(val kind: Kind, val text: String)

enum class DualPhase { IDLE, LIVE, PREP_HEADSET, REC_HEADSET, PREP_PHONE, REC_PHONE, DONE }

data class DualState(
    val phase: DualPhase = DualPhase.IDLE,
    val secondsLeft: Int = 0,
    val levelA: Float = Dsp.FLOOR_DB,
    val levelB: Float = Dsp.FLOOR_DB,
    val routedA: String = "—",
    val routedB: String = "—",
    val silencedA: Boolean? = null,
    val silencedB: Boolean? = null,
    val verdict: Verdict? = null,
    val detail: String = "",
    val recordings: List<File> = emptyList(),
)

data class OutputState(
    val step: String = "",
    val sequential: Verdict? = null,
    val sequentialDetail: String = "",
    val simultaneous: Verdict? = null,
    val simultaneousDetail: String = "",
)

data class ScanState(
    val step: String = "",
    val pairs: List<ConcurrencyScan.PairResult> = emptyList(),
    val stereo: List<ConcurrencyScan.StereoResult> = emptyList(),
    val pairVerdict: Verdict? = null,
    val stereoVerdict: Verdict? = null,
)

data class TtsState(
    val engines: List<TtsEngine.Engine> = emptyList(),
    val engine: String? = null,
    val ready: Boolean = false,
    val statuses: List<TtsEngine.LangStatus> = emptyList(),
    val selected: Lang = Languages.FOREIGN.first(),
    val headsetResult: String? = null,
    val speakerResult: String? = null,
    val verdict: Verdict? = null,
)

/** Ses kaynağı seçenekleri (AudioRecord audio source). */
val AUDIO_SOURCES = listOf(
    MediaRecorder.AudioSource.VOICE_RECOGNITION to "Konuşma tanıma (önerilen)",
    MediaRecorder.AudioSource.MIC to "Ham mikrofon",
    MediaRecorder.AudioSource.UNPROCESSED to "İşlenmemiş",
    MediaRecorder.AudioSource.VOICE_COMMUNICATION to "Görüşme (yankı engellemeli)",
    MediaRecorder.AudioSource.CAMCORDER to "Kamera",
)

fun sourceName(source: Int) = AUDIO_SOURCES.firstOrNull { it.first == source }?.second ?: "kaynak $source"

/**
 * Cihaz testi: uygulamanın dayandığı üç platform davranışını telefonda ölçer.
 * 1) Kulaklık mikrofonu ve telefon mikrofonu aynı anda ayrı ayrı kaydedilebiliyor mu?
 * 2) Kulaklık takılıyken ses ayrıca telefon hoparlörüne verilebiliyor mu?
 * 3) Sistem seslendirmesi (TTS) istenen cihaza yönlendirilebiliyor mu, hangi diller internetsiz?
 */
class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context get() = getApplication()
    private val am = AudioDevices.audioManager(app)
    private val tts = TtsEngine(app)

    var hasPermission by mutableStateOf(false); private set
    var deviceSummary by mutableStateOf(""); private set
    var inputs by mutableStateOf<List<AudioDeviceInfo>>(emptyList()); private set

    /** Test 1'de seçilebilen mikrofonlar. */
    var mics by mutableStateOf<List<AudioDeviceInfo>>(emptyList()); private set
    var outputs by mutableStateOf<List<AudioDeviceInfo>>(emptyList()); private set
    var microphones by mutableStateOf<List<String>>(emptyList()); private set

    var selectedA by mutableStateOf<Int?>(null)
    var selectedB by mutableStateOf<Int?>(null)
    var source by mutableStateOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)

    var dual by mutableStateOf(DualState()); private set
    var output by mutableStateOf(OutputState()); private set
    var scan by mutableStateOf(ScanState()); private set
    var ttsState by mutableStateOf(TtsState()); private set

    /** Geliştirici ASR testinin son satırları. */
    var benchLog by mutableStateOf<List<String>>(emptyList()); private set

    /** Aynı anda tek test çalışır (hepsi mikrofonu ya da hoparlörü kullanıyor). */
    var busy by mutableStateOf(false); private set
    val log = mutableStateListOf<String>()

    private var job: Job? = null
    private var ttsHeadsetRes: TtsEngine.SpeakResult? = null
    private var ttsSpeakerRes: TtsEngine.SpeakResult? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshDevices()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshDevices()
    }

    init {
        deviceSummary = buildDeviceSummary()
        am.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        refreshDevices()
        initTts(null)
    }

    fun onPermissionResult(granted: Boolean) {
        if (hasPermission != granted) log(if (granted) "Mikrofon izni verildi" else "Mikrofon izni verilmedi")
        hasPermission = granted
    }

    private fun refreshDevices() {
        inputs = AudioDevices.inputs(ctx)
        outputs = AudioDevices.outputs(ctx)
        microphones = AudioDevices.microphones(ctx)
        val available = AudioDevices.mics(ctx)
        mics = available
        // Kulaklık takılınca A kendiliğinden kulaklık mikrofonuna geçsin.
        val headset = AudioDevices.headsetMic(ctx)
        if (available.none { it.id == selectedA } || (headset != null && available.firstOrNull { it.id == selectedA }?.let(AudioDevices::isHeadsetInput) != true)) {
            selectedA = headset?.id
        }
        if (available.none { it.id == selectedB }) selectedB = AudioDevices.builtinMic(ctx)?.id
    }

    private fun launchTest(block: suspend CoroutineScope.() -> Unit) {
        if (busy) {
            android.util.Log.w("CanliCeviri", "başka bir test sürüyor, yeni test başlatılmadı")
            return
        }
        busy = true
        job = viewModelScope.launch {
            try {
                block()
            } finally {
                busy = false
                saveReport()
            }
        }
    }

    fun stopTest() {
        job?.cancel()
    }

    // ---------------------------------------------------------------- Test 1: iki mikrofon

    private fun pickDualDevices(): Pair<AudioDeviceInfo, AudioDeviceInfo>? {
        val a = mics.firstOrNull { it.id == selectedA }
        val b = mics.firstOrNull { it.id == selectedB }
        val problem = when {
            a == null -> Verdict(Kind.FAIL, "Kulaklık mikrofonu (A) bulunamadı. Mikrofonlu kulaklığı takıp tekrar deneyin.")
            b == null -> Verdict(Kind.FAIL, "Telefon mikrofonu (B) bulunamadı.")
            a.id == b.id -> Verdict(Kind.WARN, "A ve B için iki farklı mikrofon seçin.")
            else -> null
        }
        if (problem != null || a == null || b == null) {
            dual = DualState(phase = DualPhase.DONE, verdict = problem)
            return null
        }
        return a to b
    }

    /** Test yapmadan iki mikrofonun seviyesini canlı gösterir. */
    fun startLive() = launchTest {
        val (a, b) = pickDualDevices() ?: return@launchTest
        val capA = MicCapture("A", a, source)
        val capB = MicCapture("B", b, source)
        dual = DualState(phase = DualPhase.LIVE)
        val okA = withContext(Dispatchers.IO) { capA.start() }
        val okB = withContext(Dispatchers.IO) { capB.start() }
        log("Canlı dinleme · ${sourceName(source)} · A ${if (okA) "açıldı" else capA.error} · B ${if (okB) "açıldı" else capB.error}")
        try {
            if (!okA || !okB) {
                dual = dual.copy(phase = DualPhase.DONE, verdict = startFailure(capA, okA, capB, okB))
                return@launchTest
            }
            var n = 0
            while (isActive) {
                updateLive(DualPhase.LIVE, 0, capA, capB, checkSilenced = n++ % 10 == 0)
                delay(50)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { capA.stop(); capB.stop() }
            if (dual.phase == DualPhase.LIVE) dual = dual.copy(phase = DualPhase.IDLE)
        }
    }

    /** İki aşamalı ölçüm: önce kulaklık mikrofonuna, sonra telefona konuşulur. */
    fun startDualTest() = launchTest {
        val (a, b) = pickDualDevices() ?: return@launchTest
        val dir = File(ctx.getExternalFilesDir(null), "kayitlar").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileA = File(dir, "iki-mikrofon-$stamp-A-kulaklik.wav")
        val fileB = File(dir, "iki-mikrofon-$stamp-B-telefon.wav")
        val capA = MicCapture("A", a, source)
        val capB = MicCapture("B", b, source)
        dual = DualState(phase = DualPhase.PREP_HEADSET, secondsLeft = (PREP_MS / 1000).toInt())
        val okA = withContext(Dispatchers.IO) { capA.start(fileA) }
        val okB = withContext(Dispatchers.IO) { capB.start(fileB) }
        log("Test 1 · ${sourceName(source)} · A=${AudioDevices.describe(a)} ${if (okA) "açıldı" else capA.error} · B=${AudioDevices.describe(b)} ${if (okB) "açıldı" else capB.error}")
        try {
            if (!okA || !okB) {
                dual = dual.copy(phase = DualPhase.DONE, verdict = startFailure(capA, okA, capB, okB))
                return@launchTest
            }
            val s1 = stage(DualPhase.PREP_HEADSET, DualPhase.REC_HEADSET, capA, capB)
            val s2 = stage(DualPhase.PREP_PHONE, DualPhase.REC_PHONE, capA, capB)
            val silA = silenced(capA.sessionId)
            val silB = silenced(capB.sessionId)
            val (verdict, detail) = evaluateDual(s1, s2, capA, capB, silA, silB)
            dual = dual.copy(
                phase = DualPhase.DONE,
                verdict = verdict,
                detail = detail,
                silencedA = silA,
                silencedB = silB,
                recordings = listOf(fileA, fileB),
            )
            log("Test 1 sonucu: ${verdict.text} | $detail")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { capA.stop(); capB.stop() }
            if (dual.phase != DualPhase.DONE) dual = dual.copy(phase = DualPhase.IDLE)
        }
    }

    private fun startFailure(capA: MicCapture, okA: Boolean, capB: MicCapture, okB: Boolean): Verdict = when {
        !okA && !okB -> Verdict(Kind.FAIL, "İki kayıt da açılamadı: ${capA.error}")
        !okA -> Verdict(Kind.FAIL, "Kulaklık kaydı (A) açılamadı: ${capA.error}")
        else -> Verdict(Kind.FAIL, "İkinci kayıt (B) açılamadı, sistem aynı anda iki kayda izin vermiyor: ${capB.error}")
    }

    private suspend fun stage(prep: DualPhase, rec: DualPhase, capA: MicCapture, capB: MicCapture): List<Pair<Float, Float>> {
        runPhase(prep, PREP_MS, capA, capB, null)
        val samples = ArrayList<Pair<Float, Float>>()
        runPhase(rec, REC_MS, capA, capB, samples)
        return samples
    }

    private suspend fun runPhase(
        phase: DualPhase,
        durationMs: Long,
        capA: MicCapture,
        capB: MicCapture,
        out: MutableList<Pair<Float, Float>>?,
    ) {
        val start = SystemClock.elapsedRealtime()
        var n = 0
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - start
            if (elapsed >= durationMs) break
            out?.add(capA.smoothDb to capB.smoothDb)
            updateLive(phase, ((durationMs - elapsed + 999) / 1000).toInt(), capA, capB, checkSilenced = n++ % 10 == 0)
            delay(50)
        }
    }

    private fun updateLive(phase: DualPhase, secondsLeft: Int, capA: MicCapture, capB: MicCapture, checkSilenced: Boolean) {
        dual = dual.copy(
            phase = phase,
            secondsLeft = secondsLeft,
            levelA = capA.smoothDb,
            levelB = capB.smoothDb,
            routedA = AudioDevices.describe(capA.routedDevice),
            routedB = AudioDevices.describe(capB.routedDevice),
            silencedA = if (checkSilenced) silenced(capA.sessionId) else dual.silencedA,
            silencedB = if (checkSilenced) silenced(capB.sessionId) else dual.silencedB,
        )
    }

    /** Android 10+: sistem bu kaydı susturdu mu? */
    private fun silenced(sessionId: Int): Boolean? {
        if (Build.VERSION.SDK_INT < 29) return null
        return am.activeRecordingConfigurations
            .firstOrNull { it.clientAudioSessionId == sessionId }
            ?.isClientSilenced
    }

    private data class StageStats(val noise: Float, val loud: Float, val diff: Float, val speech: Boolean)

    /** Aşamanın en gürültülü %40'lık diliminde A−B farkı (dB). Kazanç farklarından etkilenmemek için iki aşama kıyaslanır. */
    private fun stageStats(samples: List<Pair<Float, Float>>): StageStats {
        if (samples.isEmpty()) return StageStats(Dsp.FLOOR_DB, Dsp.FLOOR_DB, 0f, false)
        val loudness = samples.map { max(it.first, it.second) }
        val noise = Dsp.percentile(loudness, 0.1)
        val top = samples.indices.sortedByDescending { loudness[it] }.take(max(1, samples.size * 4 / 10))
        val loud = top.map { loudness[it] }.average().toFloat()
        val diff = top.map { samples[it].first - samples[it].second }.average().toFloat()
        return StageStats(noise, loud, diff, loud - noise >= 8f)
    }

    private fun evaluateDual(
        s1: List<Pair<Float, Float>>,
        s2: List<Pair<Float, Float>>,
        capA: MicCapture,
        capB: MicCapture,
        silA: Boolean?,
        silB: Boolean?,
    ): Pair<Verdict, String> {
        val st1 = stageStats(s1)
        val st2 = stageStats(s2)
        val separation = st1.diff - st2.diff
        val rA = capA.routedDevice
        val rB = capB.routedDevice
        val minFrames = 2 * (PREP_MS + REC_MS) * 16 / 512 / 2
        val detail = buildString {
            append("Aşama 1 (kulaklığa): A−B ${fmt(st1.diff)} dB (ses ${fmt(st1.loud)}, gürültü ${fmt(st1.noise)}). ")
            append("Aşama 2 (telefona): A−B ${fmt(st2.diff)} dB (ses ${fmt(st2.loud)}, gürültü ${fmt(st2.noise)}). ")
            append("Ayrım ${fmt(separation)} dB. ")
            append("A: istenen ${AudioDevices.describe(capA.requestedDevice)}, bağlanan ${AudioDevices.describe(rA)}. ")
            append("B: istenen ${AudioDevices.describe(capB.requestedDevice)}, bağlanan ${AudioDevices.describe(rB)}. ")
            append("Çerçeve A/B ${capA.framesRead}/${capB.framesRead}, sıfır çerçeve ${capA.zeroFrames}/${capB.zeroFrames}")
            if (silA != null || silB != null) append(", susturma ${silA ?: "?"}/${silB ?: "?"}")
            append('.')
        }
        val verdict = when {
            capA.framesRead < minFrames -> Verdict(Kind.FAIL, "Kulaklık kaydı (A) durdu ya da hiç veri gelmedi.")
            capB.framesRead < minFrames -> Verdict(Kind.FAIL, "Telefon kaydı (B) durdu ya da hiç veri gelmedi.")
            capA.zeroFrames > capA.framesRead * 9 / 10 -> Verdict(Kind.FAIL, "Kulaklık mikrofonundan (A) hiç ses gelmiyor; sistem susturmuş olabilir.")
            capB.zeroFrames > capB.framesRead * 9 / 10 -> Verdict(Kind.FAIL, "Telefon mikrofonundan (B) hiç ses gelmiyor; sistem susturmuş olabilir.")
            silA == true || silB == true -> Verdict(Kind.FAIL, "Sistem kayıtlardan birini susturdu; iki mikrofon aynı anda kullanılamıyor.")
            rA != null && rB != null && rA.id == rB.id ->
                Verdict(Kind.FAIL, "İki kayıt da aynı mikrofona bağlandı (${AudioDevices.typeName(rA.type)}).")
            !st1.speech || !st2.speech -> Verdict(Kind.WARN, "Konuşma yeterince algılanmadı; talimatlara göre tekrar deneyin.")
            separation >= 8f -> Verdict(Kind.OK, "İki mikrofon aynı anda ve ayrı ayrı çalışıyor (ayrım ${fmt(separation)} dB).")
            separation < 3f -> Verdict(Kind.FAIL, "İki kayıt aynı sesi alıyor (ayrım ${fmt(separation)} dB); büyük ihtimalle ikisi de aynı mikrofon.")
            else -> Verdict(Kind.WARN, "Ayrım zayıf (${fmt(separation)} dB). Talimatlara daha sıkı uyarak tekrar deneyin.")
        }
        return verdict to detail
    }

    /** Konuşmadan: her kayıt türü eşleşmesini dener; ayrıca telefonun kendi mikrofonlarından stereo kayıt. */
    fun startScan() = launchTest { scanBody() }

    private suspend fun scanBody() {
        val scanner = ConcurrencyScan(am)
        scan = ScanState(step = "Başlıyor…")
        val devices = pickDualDevices()
        if (devices != null) {
            val (a, b) = devices
            val pairs = scanner.scanPairs(a, b) { scan = scan.copy(step = it) }
            val separate = pairs.filter { it.outcome == ConcurrencyScan.Outcome.SEPARATE }
            val pairVerdict = when {
                separate.isNotEmpty() -> Verdict(
                    Kind.OK,
                    "${separate.size}/${pairs.size} eşleşmede iki mikrofon ayrı çalışıyor: " +
                        separate.take(4).joinToString { "${ConcurrencyScan.shortSource(it.sourceA)}+${ConcurrencyScan.shortSource(it.sourceB)}" },
                )
                pairs.all { it.outcome == ConcurrencyScan.Outcome.SAME_DEVICE } ->
                    Verdict(Kind.FAIL, "Hiçbir eşleşmede iki ayrı mikrofon açılamadı; sistem hep tek mikrofona bağlıyor.")
                else -> Verdict(Kind.FAIL, "İki ayrı mikrofon açılamadı (${summarize(pairs)}).")
            }
            scan = scan.copy(pairs = pairs, pairVerdict = pairVerdict)
            log("Tarama (A=${AudioDevices.describe(a)}, B=${AudioDevices.describe(b)}): ${pairVerdict.text}")
            pairs.forEach { log("  ${pairLine(it)}") }
        }
        val builtin = AudioDevices.builtinMic(ctx)
        if (builtin != null) {
            val stereo = scanner.scanStereo(builtin) { scan = scan.copy(step = it) }
            val real = stereo.filter { it.outcome == ConcurrencyScan.StereoOutcome.TRUE_STEREO }
            val stereoVerdict = if (real.isNotEmpty()) {
                Verdict(Kind.OK, "Telefonun kendi iki mikrofonu tek kayıtta ayrı kanallar olarak alınabiliyor: " +
                    real.joinToString { ConcurrencyScan.shortSource(it.source) })
            } else {
                Verdict(Kind.WARN, "Stereo kayıtta iki kanal aynı; telefonun mikrofonları ayrı alınamıyor.")
            }
            scan = scan.copy(stereo = stereo, stereoVerdict = stereoVerdict)
            log("Stereo: ${stereoVerdict.text}")
            stereo.forEach { log("  ${stereoLine(it)}") }
        }
        scan = scan.copy(step = "")
    }

    private fun summarize(pairs: List<ConcurrencyScan.PairResult>) =
        pairs.groupingBy { it.outcome }.eachCount().entries.joinToString { "${outcomeName(it.key)} ${it.value}" }

    /** Müşteri için tek düğme: konuşma gerektirmeyen tüm testler sırayla, sonra rapor. */
    var allRunning by mutableStateOf(false); private set
    var allDone by mutableStateOf(false); private set

    fun runAll() = launchTest {
        allRunning = true
        allDone = false
        try {
            log("Tüm testler başladı")
            scanBody()
            delay(300)
            outputBody(simultaneous = false)
            delay(300)
            outputBody(simultaneous = true)
            delay(300)
            if (ttsState.ready) {
                speakBody(toHeadset = true)
                delay(300)
                speakBody(toHeadset = false)
            }
            log("Tüm testler bitti")
            allDone = true
        } finally {
            allRunning = false
        }
    }

    /** Geliştirici: telefondaki test sesleriyle Whisper hız/doğruluk ölçümü (adb ile tetiklenir). */
    fun runBench(spec: String) = launchTest {
        benchLog = listOf("ASR testi başlıyor: $spec")
        withContext(Dispatchers.Default) {
            AsrBench.run(ctx, spec) { line -> viewModelScope.launch { benchLog = (benchLog + line).takeLast(80) } }
        }
        log("ASR testi bitti ($spec); sonuç files/bench.txt")
    }

    /** Geliştirici: çeviri modelinin (Hy-MT2) hızını ve çıktısını ölçer (adb ile tetiklenir). */
    fun runMtBench(spec: String) = launchTest {
        benchLog = listOf("Çeviri testi başlıyor")
        withContext(Dispatchers.Default) {
            MtBench.run(ctx, spec) { line -> viewModelScope.launch { benchLog = (benchLog + line).takeLast(80) } }
        }
        log("Çeviri testi bitti; sonuç files/mt.txt")
    }

    // ---------------------------------------------------------------- Test 2: kulaklık takılıyken hoparlör

    /** Telefon mikrofonunu açık tutup 1 kHz ve 750 Hz'deki ses seviyesini zamanla kaydeder. */
    private class ToneProbe(mic: AudioDeviceInfo, source: Int) {
        class Sample(val t: Long, val g1000: Float, val g750: Float)

        private val samples = ArrayList<Sample>()
        val capture = MicCapture("ölçüm", mic, source, onFrame = { f ->
            val s = Sample(SystemClock.elapsedRealtime(), Dsp.goertzelDb(f, 16_000, 1000.0), Dsp.goertzelDb(f, 16_000, 750.0))
            synchronized(samples) { samples.add(s) }
        })

        fun window(from: Long, to: Long): List<Sample> = synchronized(samples) { samples.filter { it.t in from..to } }
    }

    fun startOutputTest(simultaneous: Boolean) = launchTest { outputBody(simultaneous) }

    private suspend fun outputBody(simultaneous: Boolean) {
        val spk = AudioDevices.speaker(ctx)
        val hs = AudioDevices.headsetOutput(ctx)
        val mic = AudioDevices.builtinMic(ctx)
        if (spk == null || mic == null) {
            setOutputVerdict(simultaneous, Verdict(Kind.FAIL, "Telefon hoparlörü ya da mikrofonu bulunamadı."), "")
            return
        }
        if (hs == null) {
            setOutputVerdict(simultaneous, Verdict(Kind.WARN, "Kulaklık takılı değil. Kulaklığı takıp tekrar deneyin."), "")
            return
        }
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val probe = ToneProbe(mic, if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC)
        output = output.copy(step = "Ölçüm mikrofonu açılıyor…")
        if (!withContext(Dispatchers.IO) { probe.capture.start() }) {
            setOutputVerdict(simultaneous, Verdict(Kind.FAIL, "Ölçüm için telefon mikrofonu açılamadı: ${probe.capture.error}"), "")
            return
        }
        try {
            delay(700) // taban gürültü
            if (simultaneous) runSimultaneous(probe, spk, hs) else runSequential(probe, spk, hs)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { probe.capture.stop() }
            output = output.copy(step = "")
        }
    }

    private suspend fun runSequential(probe: ToneProbe, spk: AudioDeviceInfo, hs: AudioDeviceInfo) {
        val t0 = SystemClock.elapsedRealtime()
        val base = Dsp.median(probe.window(t0 - 600, t0).map { it.g1000 })

        output = output.copy(step = "🔊 Hoparlörden bip…")
        val t1 = SystemClock.elapsedRealtime()
        val routedSpk = withContext(Dispatchers.IO) { playBeeps(spk, 1000.0) }
        delay(300)
        val spkLevel = Dsp.percentile(probe.window(t1, SystemClock.elapsedRealtime()).map { it.g1000 }, 0.9)

        output = output.copy(step = "🎧 Kulaklıktan bip…")
        delay(300)
        val t2 = SystemClock.elapsedRealtime()
        val routedHs = withContext(Dispatchers.IO) { playBeeps(hs, 1000.0) }
        delay(300)
        val hsLevel = Dsp.percentile(probe.window(t2, SystemClock.elapsedRealtime()).map { it.g1000 }, 0.9)

        val spkRise = spkLevel - base
        val hsRise = hsLevel - base
        val detail = "Hoparlör isteği → sistem: ${AudioDevices.describe(routedSpk)}. Kulaklık isteği → sistem: ${AudioDevices.describe(routedHs)}. " +
            "Telefon mikrofonunda 1 kHz: taban ${fmt(base)} dB, hoparlör bipi ${fmt(spkRise)} dB, kulaklık bipi ${fmt(hsRise)} dB yükseltti."
        val verdict = when {
            routedSpk != null && AudioDevices.isHeadsetOutput(routedSpk) ->
                Verdict(Kind.FAIL, "Sistem hoparlör isteğini dinlemedi, ses kulaklığa gitti.")
            spkRise < 10f -> Verdict(Kind.FAIL, "Telefon mikrofonu hoparlörden bip duymadı. Medya sesi kısık olabilir; açıp tekrar deneyin.")
            spkRise >= 15f && spkRise - hsRise >= 10f -> Verdict(Kind.OK, "Kulaklık takılıyken ses ayrıca hoparlöre verilebiliyor.")
            else -> Verdict(Kind.WARN, "Sonuç net değil; kulaklığı telefondan uzak tutup tekrar deneyin.")
        }
        setOutputVerdict(false, verdict, detail)
    }

    private suspend fun runSimultaneous(probe: ToneProbe, spk: AudioDeviceInfo, hs: AudioDeviceInfo) {
        val t0 = SystemClock.elapsedRealtime()
        val base = probe.window(t0 - 600, t0)
        val base1000 = Dsp.median(base.map { it.g1000 })
        val base750 = Dsp.median(base.map { it.g750 })

        output = output.copy(step = "🔊🎧 İkisinden aynı anda bip…")
        val t1 = SystemClock.elapsedRealtime()
        val (routedSpk, routedHs) = withContext(Dispatchers.IO) {
            coroutineScope {
                val a = async { playBeeps(spk, 1000.0) }
                val b = async { playBeeps(hs, 750.0) }
                a.await() to b.await()
            }
        }
        delay(300)
        val w = probe.window(t1, SystemClock.elapsedRealtime())
        val spkRise = Dsp.percentile(w.map { it.g1000 }, 0.9) - base1000
        val hsLeak = Dsp.percentile(w.map { it.g750 }, 0.9) - base750
        val detail = "Hoparlör (1 kHz) → ${AudioDevices.describe(routedSpk)}; kulaklık (750 Hz) → ${AudioDevices.describe(routedHs)}. " +
            "Telefon mikrofonunda 1 kHz ${fmt(spkRise)} dB, 750 Hz ${fmt(hsLeak)} dB yükseldi."
        val ok = routedSpk?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER &&
            routedHs != null && AudioDevices.isHeadsetOutput(routedHs) &&
            spkRise >= 15f && spkRise - hsLeak >= 10f
        val verdict = if (ok) {
            Verdict(Kind.OK, "Aynı anda iki farklı çıkış çalışıyor.")
        } else {
            Verdict(Kind.WARN, "Aynı anda iki ayrı çıkış olmuyor ya da net değil. Sırayla çalışmak uygulama için yeterli.")
        }
        setOutputVerdict(true, verdict, detail)
    }

    private fun playBeeps(device: AudioDeviceInfo, freq: Double): AudioDeviceInfo? {
        val player = PcmPlayer(16_000, device)
        player.write(Dsp.beeps(16_000, freq, BEEP_AMPLITUDE))
        player.finish()
        return player.routedDevice
    }

    private fun setOutputVerdict(simultaneous: Boolean, verdict: Verdict, detail: String) {
        output = if (simultaneous) {
            output.copy(simultaneous = verdict, simultaneousDetail = detail)
        } else {
            output.copy(sequential = verdict, sequentialDetail = detail)
        }
        log("Test 2${if (simultaneous) "b (aynı anda)" else " (sırayla)"}: ${verdict.text} | $detail")
    }

    // ---------------------------------------------------------------- Test 3: seslendirme

    fun initTts(enginePackage: String?) {
        ttsState = ttsState.copy(ready = false, statuses = emptyList())
        // Google motoru dil kapsamı en geniş olan; kuruluysa onu kullan.
        val preferred = enginePackage ?: GOOGLE_TTS.takeIf { isPackageInstalled(it) }
        tts.init(preferred) { ok ->
            viewModelScope.launch {
                ttsState = ttsState.copy(ready = ok, engine = tts.enginePackage, engines = tts.engines())
                log("Seslendirme motoru: ${tts.enginePackage ?: "?"} (${if (ok) "hazır" else "açılamadı"})")
                if (ok) refreshTtsStatuses()
            }
        }
    }

    fun refreshTtsStatuses() {
        viewModelScope.launch {
            val statuses = withContext(Dispatchers.Default) { tts.statuses(Languages.ALL) }
            ttsState = ttsState.copy(statuses = statuses)
        }
    }

    fun selectTtsLang(lang: Lang) {
        ttsState = ttsState.copy(selected = lang)
    }

    private fun isPackageInstalled(pkg: String) = try {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: Exception) {
        false
    }

    private suspend fun speakAwait(text: String, lang: Lang, device: AudioDeviceInfo?): TtsEngine.SpeakResult =
        suspendCancellableCoroutine { cont ->
            tts.speak(text, lang, device) { r -> if (cont.isActive) cont.resume(r) }
        }

    /**
     * Geliştirici: 11 dilin örnek cümlesini sırayla hoparlörden okur, kullanılan sesi files/ses.txt'ye yazar
     * (her dil kendi sesiyle mi okunuyor). adb … --es ses 1
     */
    fun runVoiceCheck() = launchTest {
        val lines = ArrayList<String>()
        fun emit(line: String) {
            lines += line
            android.util.Log.i("CanliCeviriBench", line)
            benchLog = (benchLog + line).takeLast(80)
        }
        benchLog = emptyList()
        var waited = 0
        while (!ttsState.ready && waited < 10_000) {
            delay(200)
            waited += 200
        }
        emit("Seslendirme denetimi · motor ${tts.enginePackage ?: "?"} · ${if (ttsState.ready) "hazır" else "açılamadı"}")
        val speaker = AudioDevices.speaker(ctx)
        for (lang in Languages.ALL) {
            val r = speakAwait(lang.sample, lang, speaker)
            emit(
                "${lang.code} ${if (r.ok) "✓" else "✗"} ses=${r.voice ?: "-"} ilk-ses=${r.firstAudioMs ?: "-"} ms " +
                    "toplam=${r.totalMs} ms${r.error?.let { " · $it" } ?: ""}"
            )
            delay(300)
        }
        File(ctx.getExternalFilesDir(null), "ses.txt").writeText(lines.joinToString("\n") + "\nbitti\n")
    }

    /** [toHeadset]: Türkçe örneği kulaklığa; değilse seçili yabancı dili hoparlöre okur. */
    fun speakTest(toHeadset: Boolean) = launchTest { speakBody(toHeadset) }

    private suspend fun speakBody(toHeadset: Boolean) {
        val lang = if (toHeadset) Languages.TURKISH else ttsState.selected
        val device = if (toHeadset) AudioDevices.headsetOutput(ctx) else AudioDevices.speaker(ctx)
        val target = if (toHeadset) "kulaklık" else "hoparlör"
        if (device == null) {
            val msg = if (toHeadset) "Kulaklık takılı değil." else "Hoparlör bulunamadı."
            ttsState = if (toHeadset) ttsState.copy(headsetResult = msg) else ttsState.copy(speakerResult = msg)
            ttsState = ttsState.copy(verdict = Verdict(Kind.WARN, msg))
            return
        }
        val r = speakAwait(lang.sample, lang, device)
        val text = if (r.ok) {
            "${lang.nameTr} → $target: sistem ${AudioDevices.describe(r.routed)} · ilk ses ${r.firstAudioMs ?: "?"} ms · " +
                "toplam ${r.totalMs} ms · ${if (r.streamed) "akışla" else "dosyadan"} · ses ${r.voice ?: "varsayılan"}"
        } else {
            "${lang.nameTr} → $target: hata (${r.error})"
        }
        if (toHeadset) {
            ttsHeadsetRes = r
            ttsState = ttsState.copy(headsetResult = text)
        } else {
            ttsSpeakerRes = r
            ttsState = ttsState.copy(speakerResult = text)
        }
        ttsState = ttsState.copy(verdict = ttsVerdict())
        log("Test 3: $text")
    }

    private fun ttsVerdict(): Verdict? {
        val h = ttsHeadsetRes
        val s = ttsSpeakerRes
        if (h == null && s == null) return null
        val problems = mutableListOf<String>()
        if (h != null) {
            val routed = h.routed
            if (!h.ok) problems += "Türkçe okunamadı (${h.error})"
            else if (routed != null && !AudioDevices.isHeadsetOutput(routed)) problems += "Türkçe kulaklığa değil ${AudioDevices.typeName(routed.type)} cihazına gitti"
        }
        if (s != null) {
            val routed = s.routed
            if (!s.ok) problems += "yabancı dil okunamadı (${s.error})"
            else if (routed != null && routed.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) problems += "yabancı dil hoparlöre değil ${AudioDevices.typeName(routed.type)} cihazına gitti"
        }
        return when {
            problems.isNotEmpty() -> Verdict(Kind.FAIL, problems.joinToString("; ").replaceFirstChar { it.uppercase(Locale.forLanguageTag("tr")) } + ".")
            h != null && s != null -> Verdict(Kind.OK, "Türkçe kulaklıktan, yabancı dil hoparlörden çıkıyor.")
            else -> Verdict(Kind.WARN, "Diğer yönü de deneyin.")
        }
    }

    // ---------------------------------------------------------------- Rapor

    private fun buildDeviceSummary(): String = com.alterra.canliceviri.report.DeviceInfo.summary(ctx)

    fun buildReport(): String = buildString {
        fun line(v: Verdict?) = when (v?.kind) {
            null -> "denenmedi"
            Kind.OK -> "✅ ${v.text}"
            Kind.WARN -> "⚠️ ${v.text}"
            Kind.FAIL -> "❌ ${v.text}"
        }
        appendLine("Canlı Çeviri · Cihaz testi ${BuildConfig.VERSION_NAME}")
        appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        appendLine(deviceSummary)
        appendLine()
        appendLine("ÖZET")
        appendLine("1) İki mikrofon aynı anda: ${line(dual.verdict)}")
        appendLine("2) Kulaklık takılıyken hoparlör: ${line(output.sequential)}")
        appendLine("2b) Aynı anda iki çıkış: ${line(output.simultaneous)}")
        appendLine("3) Seslendirme yönlendirme: ${line(ttsState.verdict)}")
        appendLine("Tarama (iki mikrofon): ${line(scan.pairVerdict)}")
        appendLine("Tarama (telefon stereo): ${line(scan.stereoVerdict)}")
        appendLine("Kaynak: ${sourceName(source)}")
        appendLine()
        appendLine("AYRINTI")
        if (dual.detail.isNotEmpty()) appendLine("1) ${dual.detail}")
        if (output.sequentialDetail.isNotEmpty()) appendLine("2) ${output.sequentialDetail}")
        if (output.simultaneousDetail.isNotEmpty()) appendLine("2b) ${output.simultaneousDetail}")
        appendLine("3) Motor ${ttsState.engine ?: "?"} · ${ttsState.headsetResult ?: "-"} · ${ttsState.speakerResult ?: "-"}")
        appendLine("Seslendirme dilleri: " + ttsState.statuses.joinToString(", ") { "${it.lang.code} ${stateShort(it.state)}" })
        if (scan.pairs.isNotEmpty()) {
            appendLine("Tarama ayrıntısı:")
            scan.pairs.forEach { appendLine("  ${pairLine(it)}") }
        }
        if (scan.stereo.isNotEmpty()) {
            appendLine("Stereo ayrıntısı:")
            scan.stereo.forEach { appendLine("  ${stereoLine(it)}") }
        }
        appendLine()
        appendLine("GİRİŞLER")
        inputs.forEach { appendLine("• ${AudioDevices.describeDetailed(it)}") }
        appendLine("ÇIKIŞLAR")
        outputs.forEach { appendLine("• ${AudioDevices.describeDetailed(it)}") }
        appendLine("FİZİKSEL MİKROFONLAR (${microphones.size})")
        microphones.forEach { appendLine("• $it") }
        appendLine()
        appendLine("GÜNLÜK")
        log.takeLast(40).forEach { appendLine(it) }
    }

    /** Raporu dosyaya da yazar; telefon Mac'e bağlanınca adb ile okunabiliyor. */
    fun saveReport() {
        val text = buildReport()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                File(ctx.getExternalFilesDir(null), "rapor.txt").writeText(text)
            } catch (_: Exception) {
            }
        }
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        log.add("$time $message")
        while (log.size > 200) log.removeAt(0)
    }

    override fun onCleared() {
        job?.cancel()
        am.unregisterAudioDeviceCallback(deviceCallback)
        tts.shutdown()
    }

    companion object {
        const val GOOGLE_TTS = "com.google.android.tts"
        const val PREP_MS = 3000L
        const val REC_MS = 6000L
        const val BEEP_AMPLITUDE = 0.25

        fun fmt(v: Float) = "%+.1f".format(v)

        fun outcomeName(o: ConcurrencyScan.Outcome) = when (o) {
            ConcurrencyScan.Outcome.SEPARATE -> "ayrı"
            ConcurrencyScan.Outcome.SAME_DEVICE -> "aynı mikrofon"
            ConcurrencyScan.Outcome.SILENCED -> "susturuldu"
            ConcurrencyScan.Outcome.FAILED -> "açılamadı"
        }

        fun pairLine(p: ConcurrencyScan.PairResult) = buildString {
            append("${ConcurrencyScan.shortSource(p.sourceA)}+${ConcurrencyScan.shortSource(p.sourceB)}: ${outcomeName(p.outcome)}")
            append(" · A→${p.routedA?.let { AudioDevices.typeName(it.type) + (if (Build.VERSION.SDK_INT >= 28) " " + it.address else "") } ?: "?"}")
            append(" · B→${p.routedB?.let { AudioDevices.typeName(it.type) + (if (Build.VERSION.SDK_INT >= 28) " " + it.address else "") } ?: "?"}")
            p.similarity?.let { append(" · benzerlik ${"%.2f".format(it)}") }
            if (p.note.isNotEmpty()) append(" · ${p.note}")
        }

        fun stereoLine(r: ConcurrencyScan.StereoResult) = buildString {
            append("${ConcurrencyScan.shortSource(r.source)}: ")
            append(
                when (r.outcome) {
                    ConcurrencyScan.StereoOutcome.TRUE_STEREO -> "gerçek stereo"
                    ConcurrencyScan.StereoOutcome.MONO_COPY -> "tek mikrofon kopyası"
                    ConcurrencyScan.StereoOutcome.FAILED -> "açılamadı"
                }
            )
            r.similarity?.let { append(" · benzerlik ${"%.2f".format(it)}") }
            if (r.note.isNotEmpty()) append(" · ${r.note}")
        }

        fun stateShort(s: TtsEngine.State) = when (s) {
            TtsEngine.State.READY -> "hazır"
            TtsEngine.State.NEEDS_DOWNLOAD -> "indirilmeli"
            TtsEngine.State.NETWORK_ONLY -> "internetle"
            TtsEngine.State.UNSUPPORTED -> "yok"
            TtsEngine.State.UNKNOWN -> "?"
        }
    }
}
