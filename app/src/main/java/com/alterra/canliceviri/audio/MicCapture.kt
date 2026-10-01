package com.alterra.canliceviri.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.File
import kotlin.math.exp

/**
 * Tek mikrofondan 16 kHz mono kayıt; çerçeveler (varsayılan 32 ms) ayrı bir iş parçacığında okunur.
 * İstenen cihaz setPreferredDevice ile verilir, sistemin gerçekte bağladığı cihaz [routedDevice]'dır.
 * İkisi farklıysa sistem isteği dinlememiş demektir.
 */
class MicCapture(
    val label: String,
    val requestedDevice: AudioDeviceInfo?,
    val source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    val sampleRate: Int = 16_000,
    val frameSamples: Int = 512,
    /** 1 = mono, 2 = stereo (telefonun iki mikrofonu; örnekler L,R,L,R… sırasıyla gelir). */
    val channels: Int = 1,
    /** Sistemin gürültü bastırma ve otomatik kazanç efektleri (varsa) bu kayda uygulansın. */
    private val enhance: Boolean = false,
    private val onFrame: ((ShortArray) -> Unit)? = null,
) {
    /** Uygulanan efektler ("gürültü bastırma, otomatik kazanç" / "yok"). */
    @Volatile var effectsInfo: String = "yok"; private set
    private val effects = ArrayList<AudioEffect>()

    @Volatile var routedDevice: AudioDeviceInfo? = null; private set
    @Volatile var error: String? = null; private set

    /** Son çerçevenin seviyesi. */
    @Volatile var levelDb: Float = Dsp.FLOOR_DB; private set

    /** ~100 ms'lik üstel ortalama; iki mikrofonu karşılaştırmak için. */
    @Volatile var smoothDb: Float = Dsp.FLOOR_DB; private set
    @Volatile var maxDb: Float = Dsp.FLOOR_DB; private set
    @Volatile var framesRead: Long = 0; private set

    /** Tamamen sıfır gelen çerçeveler; sistem kaydı susturunca böyle olur. */
    @Volatile var zeroFrames: Long = 0; private set

    /** Art arda gelen tamamen sıfır çerçeve sayısı: sistem kaydı susturduysa (arama) hep sıfır gelir. */
    @Volatile var zeroRun: Int = 0; private set
    var sessionId: Int = 0; private set

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var wav: WavWriter? = null
    @Volatile private var running = false
    private var smoothPower = 0.0
    private val alpha = exp(-frameSamples.toDouble() / sampleRate / 0.1)
    private val channelMask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

    private val routingListener = AudioRouting.OnRoutingChangedListener { router ->
        routedDevice = router.routedDevice
    }

    @SuppressLint("MissingPermission")
    fun start(recordTo: File? = null): Boolean {
        val minBytes = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) {
            error = "$sampleRate Hz kayıt desteklenmiyor"
            return false
        }
        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBytes * 2, sampleRate / 5 * 2 * channels))
                .build()
        } catch (e: Exception) {
            error = "Kayıt açılamadı: ${e.message}"
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            error = "Kayıt başlatılamadı (durum ${rec.state})"
            rec.release()
            return false
        }
        if (requestedDevice != null) rec.setPreferredDevice(requestedDevice)
        rec.addOnRoutingChangedListener(routingListener, Handler(Looper.getMainLooper()))
        try {
            rec.startRecording()
        } catch (e: Exception) {
            error = "Kayıt başlamadı: ${e.message}"
            rec.release()
            return false
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            error = "Kayıt başlamadı (mikrofonu başka bir uygulama kullanıyor olabilir)"
            rec.release()
            return false
        }
        record = rec
        sessionId = rec.audioSessionId
        if (enhance) attachEffects(sessionId)
        rec.routedDevice?.let { routedDevice = it }
        wav = recordTo?.let { WavWriter(it, sampleRate, channels) }
        running = true
        thread = Thread({ readLoop(rec) }, "mic-$label").apply { start() }
        return true
    }

    private fun readLoop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val total = frameSamples * channels
        val buf = ShortArray(total)
        while (running) {
            var off = 0
            while (off < total && running) {
                val n = rec.read(buf, off, total - off)
                if (n < 0) {
                    if (running) error = "Okuma hatası ($n)"
                    running = false
                    break
                }
                off += n
            }
            if (off < total) break

            val p = Dsp.meanPower(buf)
            smoothPower = alpha * smoothPower + (1 - alpha) * p
            val db = Dsp.powerToDb(p)
            levelDb = db
            smoothDb = Dsp.powerToDb(smoothPower)
            if (db > maxDb) maxDb = db
            if (Dsp.isAllZero(buf)) {
                zeroFrames++
                zeroRun++
            } else {
                zeroRun = 0
            }
            framesRead++
            wav?.write(buf)
            onFrame?.invoke(buf.copyOf())
            if (routedDevice == null) rec.routedDevice?.let { routedDevice = it }
        }
    }

    private fun attachEffects(session: Int) {
        val applied = ArrayList<String>()
        fun add(name: String, effect: AudioEffect?) {
            if (effect == null) return
            if (effect.setEnabled(true) == AudioEffect.SUCCESS) {
                effects += effect
                applied += name
            } else {
                effect.release()
            }
        }
        try {
            if (NoiseSuppressor.isAvailable()) add("gürültü bastırma", NoiseSuppressor.create(session))
            if (AutomaticGainControl.isAvailable()) add("otomatik kazanç", AutomaticGainControl.create(session))
        } catch (e: Exception) {
            Log.w("CanliCeviri", "ses efekti uygulanamadı", e)
        }
        effectsInfo = applied.ifEmpty { listOf("yok") }.joinToString(", ")
    }

    fun stop() {
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        running = false
        val rec = record
        record = null
        if (rec != null) {
            try {
                rec.stop() // okuma iş parçacığını bekletmeden serbest bırakır
            } catch (_: IllegalStateException) {
            }
        }
        thread?.join(1000)
        thread = null
        if (rec != null) {
            rec.removeOnRoutingChangedListener(routingListener)
            rec.release()
        }
        wav?.close()
        wav = null
    }
}
