package com.alterra.canliceviri.diag

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import com.alterra.canliceviri.audio.MicCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Konuşma gerektirmeyen tarama.
 * - Çift kayıt: A ve B mikrofonları her kayıt türü eşleşmesiyle aynı anda açılır; sistemin bağladığı
 *   cihazlara, susturmaya ve iki dalga biçiminin benzerliğine bakılır.
 * - Stereo: telefonun kendi mikrofonundan iki kanallı kayıt; kanallar gerçekten farklı mikrofonlar mı?
 */
class ConcurrencyScan(private val am: AudioManager) {

    enum class Outcome { SEPARATE, SAME_DEVICE, SILENCED, FAILED }

    data class PairResult(
        val sourceA: Int,
        val sourceB: Int,
        val outcome: Outcome,
        val routedA: AudioDeviceInfo?,
        val routedB: AudioDeviceInfo?,
        val similarity: Float?,
        val note: String,
    )

    enum class StereoOutcome { TRUE_STEREO, MONO_COPY, FAILED }

    data class StereoResult(
        val source: Int,
        val outcome: StereoOutcome,
        val similarity: Float?,
        val routed: AudioDeviceInfo?,
        val note: String,
    )

    /** Son örnekleri tutan halka arabellek. */
    private class Ring(size: Int) {
        private val buf = ShortArray(size)
        private var pos = 0
        private var filled = 0

        @Synchronized
        fun add(frame: ShortArray) {
            for (v in frame) {
                buf[pos] = v
                pos = (pos + 1) % buf.size
            }
            filled = min(buf.size, filled + frame.size)
        }

        @Synchronized
        fun snapshot(): ShortArray {
            val n = filled
            val start = (pos - n + buf.size) % buf.size
            return ShortArray(n) { buf[(start + it) % buf.size] }
        }
    }

    fun sourcesToTry(): List<Int> {
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return listOfNotNull(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.UNPROCESSED.takeIf { unprocessed },
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.CAMCORDER,
        )
    }

    suspend fun scanPairs(
        a: AudioDeviceInfo,
        b: AudioDeviceInfo,
        onProgress: (String) -> Unit,
    ): List<PairResult> {
        val sources = sourcesToTry()
        val results = ArrayList<PairResult>()
        var i = 0
        for (sa in sources) for (sb in sources) {
            i++
            onProgress("Deneniyor $i/${sources.size * sources.size}: ${shortSource(sa)} + ${shortSource(sb)}")
            results += tryPair(a, sa, b, sb)
            delay(200) // sürücü kaydı kapatsın
        }
        return results
    }

    private suspend fun tryPair(a: AudioDeviceInfo, sa: Int, b: AudioDeviceInfo, sb: Int): PairResult {
        val ringA = Ring(WINDOW)
        val ringB = Ring(WINDOW)
        val capA = MicCapture("A", a, sa, onFrame = ringA::add)
        val capB = MicCapture("B", b, sb, onFrame = ringB::add)
        try {
            val okA = withContext(Dispatchers.IO) { capA.start() }
            if (!okA) return PairResult(sa, sb, Outcome.FAILED, null, null, null, "A açılamadı: ${capA.error}")
            val okB = withContext(Dispatchers.IO) { capB.start() }
            if (!okB) return PairResult(sa, sb, Outcome.FAILED, capA.routedDevice, null, null, "B açılamadı: ${capB.error}")
            delay(LISTEN_MS)
            val silA = silenced(capA.sessionId) == true || capA.zeroFrames * 10 > capA.framesRead * 9
            val silB = silenced(capB.sessionId) == true || capB.zeroFrames * 10 > capB.framesRead * 9
            val rA = capA.routedDevice
            val rB = capB.routedDevice
            val sim = withContext(Dispatchers.Default) { similarity(ringA.snapshot(), ringB.snapshot(), maxLag = 1024) }
            val outcome = when {
                capA.framesRead == 0L || capB.framesRead == 0L -> Outcome.FAILED
                silA || silB -> Outcome.SILENCED
                rA != null && rB != null && rA.id == rB.id -> Outcome.SAME_DEVICE
                sim != null && sim >= IDENTICAL -> Outcome.SAME_DEVICE
                else -> Outcome.SEPARATE
            }
            val note = when {
                capA.framesRead == 0L || capB.framesRead == 0L -> "veri gelmedi"
                silA || silB -> "susturulan: ${if (silA) "A" else ""}${if (silB) "B" else ""}"
                else -> ""
            }
            return PairResult(sa, sb, outcome, rA, rB, sim, note)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                capB.stop()
                capA.stop()
            }
        }
    }

    suspend fun scanStereo(mic: AudioDeviceInfo, onProgress: (String) -> Unit): List<StereoResult> {
        val sources = sourcesToTry()
        val results = ArrayList<StereoResult>()
        for ((i, s) in sources.withIndex()) {
            onProgress("Stereo deneniyor ${i + 1}/${sources.size}: ${shortSource(s)}")
            results += tryStereo(mic, s)
            delay(200)
        }
        return results
    }

    private suspend fun tryStereo(mic: AudioDeviceInfo, source: Int): StereoResult {
        val ring = Ring(WINDOW * 2)
        val cap = MicCapture("stereo", mic, source, channels = 2, onFrame = ring::add)
        try {
            if (!withContext(Dispatchers.IO) { cap.start() }) {
                return StereoResult(source, StereoOutcome.FAILED, null, null, "açılamadı: ${cap.error}")
            }
            delay(LISTEN_MS)
            val interleaved = ring.snapshot()
            val n = interleaved.size / 2
            val left = ShortArray(n) { interleaved[2 * it] }
            val right = ShortArray(n) { interleaved[2 * it + 1] }
            if (n == 0) return StereoResult(source, StereoOutcome.FAILED, null, cap.routedDevice, "veri gelmedi")
            val identical = left.contentEquals(right)
            val sim = withContext(Dispatchers.Default) { similarity(left, right, maxLag = 32) }
            val outcome = when {
                identical -> StereoOutcome.MONO_COPY
                sim != null && sim >= IDENTICAL -> StereoOutcome.MONO_COPY
                sim == null -> StereoOutcome.FAILED
                else -> StereoOutcome.TRUE_STEREO
            }
            return StereoResult(source, outcome, sim, cap.routedDevice, if (identical) "iki kanal birebir aynı" else "")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { cap.stop() }
        }
    }

    private fun silenced(sessionId: Int): Boolean? {
        if (Build.VERSION.SDK_INT < 29) return null
        return am.activeRecordingConfigurations.firstOrNull { it.clientAudioSessionId == sessionId }?.isClientSilenced
    }

    companion object {
        private const val WINDOW = 8192 // ~0,5 sn
        private const val LISTEN_MS = 900L

        /** Bu benzerliğin üstü aynı mikrofon sayılır. */
        const val IDENTICAL = 0.98f

        fun shortSource(s: Int) = when (s) {
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "Tanıma"
            MediaRecorder.AudioSource.MIC -> "Ham"
            MediaRecorder.AudioSource.UNPROCESSED -> "İşlenmemiş"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "Görüşme"
            MediaRecorder.AudioSource.CAMCORDER -> "Kamera"
            else -> "$s"
        }

        /**
         * İki kaydın en fazla [maxLag] örnek kaydırmayla en yüksek normalize çapraz ilintisi (0..1).
         * Önce fark alınıyor (x[n]-x[n-1]); böylece iki ayrı mikrofonda da benzer duyulan alçak
         * frekanslı uğultu sonucu şişirmiyor. Sinyal yoksa null.
         */
        fun similarity(x: ShortArray, y: ShortArray, maxLag: Int): Float? {
            val n = min(x.size, y.size)
            if (n < 2048) return null
            val xd = DoubleArray(n - 1) { (x[x.size - n + it + 1] - x[x.size - n + it]).toDouble() }
            val yd = DoubleArray(n - 1) { (y[y.size - n + it + 1] - y[y.size - n + it]).toDouble() }
            val m = n - 1
            var best = 0.0
            var any = false
            val lagLimit = min(maxLag, m / 2)
            for (lag in -lagLimit..lagLimit) {
                var sxy = 0.0
                var sxx = 0.0
                var syy = 0.0
                val i0 = max(0, -lag)
                val i1 = min(m, m - lag)
                for (i in i0 until i1) {
                    val p = xd[i]
                    val q = yd[i + lag]
                    sxy += p * q
                    sxx += p * p
                    syy += q * q
                }
                if (sxx > 0 && syy > 0) {
                    any = true
                    val r = abs(sxy) / sqrt(sxx * syy)
                    if (r > best) best = r
                }
            }
            return if (any) best.toFloat() else null
        }
    }
}
