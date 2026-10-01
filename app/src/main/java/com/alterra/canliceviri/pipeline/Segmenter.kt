package com.alterra.canliceviri.pipeline

import com.alterra.canliceviri.asr.SileroVad
import com.alterra.canliceviri.asr.toFloatPcm
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Sürekli sesi konuşma parçalarına böler (Silero VAD, 32 ms çerçeve).
 * Konuşma başlamadan önceki 320 ms de parçaya eklenir (ilk hece kesilmesin).
 * Çift mikrofonda konuşma çerçevelerindeki (kulaklık − telefon) seviye farkı ortalanır;
 * kimin konuştuğunu bu fark söylüyor.
 */
class Segmenter(
    private val vadUser: SileroVad?,
    private val vadPartner: SileroVad,
    var silenceMs: Int = 600,
    private val threshold: Float = 0.5f,
    private val maxSegmentMs: Int = 15_000,
    private val minSpeechMs: Int = 250,
) {
    class Segment(
        val userPcm: ShortArray?,
        val partnerPcm: ShortArray,
        val speechMs: Int,
        /** Konuşma çerçevelerinde ortalama (kulaklık − telefon) seviye farkı, dB. Tek mikrofonda null. */
        val meanDiffDb: Float?,
        val endedAt: Long,
        /** Bas-konuş: kim konuştuğu düğmeden belli, seviye farkına ve dile bakılmaz. */
        val forcedSpeaker: Speaker? = null,
    )

    private val preRollFrames = 10
    private val preUser = ArrayDeque<ShortArray>()
    private val prePartner = ArrayDeque<ShortArray>()
    private val bufUser = PcmBuffer()
    private val bufPartner = PcmBuffer()
    private var inSpeech = false
    private var onsetCount = 0
    private var silentFrames = 0
    private var speechFrames = 0
    private var totalFrames = 0
    private var diffSum = 0.0
    private var diffCount = 0

    /** Son çerçevenin konuşma olasılığı (arayüz için). */
    @Volatile var lastProb = 0f; private set
    @Volatile var lastUserProb = 0f; private set
    @Volatile var lastPartnerProb = 0f; private set
    val speaking: Boolean get() = inSpeech

    /** Tamamlanan parça varsa döndürür. */
    fun process(f: AudioFrontEnd.Frames): Segment? {
        val pUser = if (vadUser != null && f.user != null) vadUser.probability(f.user.toFloatPcm()) else 0f
        val pPartner = vadPartner.probability(f.partner.toFloatPcm())
        val p = max(pUser, pPartner)
        lastProb = p
        lastUserProb = pUser
        lastPartnerProb = pPartner

        if (!inSpeech) {
            push(preUser, f.user)
            push(prePartner, f.partner)
            onsetCount = if (p >= threshold) onsetCount + 1 else 0
            if (onsetCount >= 2) {
                inSpeech = true
                silentFrames = 0
                speechFrames = 0
                totalFrames = 0
                diffSum = 0.0
                diffCount = 0
                preUser.forEach { bufUser.append(it) }
                prePartner.forEach { bufPartner.append(it) }
                totalFrames = prePartner.size
                preUser.clear()
                prePartner.clear()
            }
            return null
        }

        f.user?.let { bufUser.append(it) }
        bufPartner.append(f.partner)
        totalFrames++
        if (p >= threshold * 0.7f) {
            silentFrames = 0
            speechFrames++
            if (f.user != null) {
                diffSum += (f.levelUser - f.levelPartner).toDouble()
                diffCount++
            }
        } else {
            silentFrames++
        }

        val silenceFrames = silenceMs / FRAME_MS
        val tooLong = totalFrames * FRAME_MS >= maxSegmentMs
        if (silentFrames < silenceFrames && !tooLong) return null

        // Parça bitti. Sondaki sessizliğin çoğunu at (200 ms kalsın).
        val trim = (silentFrames - 200 / FRAME_MS).coerceAtLeast(0) * FRAME
        val segment = Segment(
            userPcm = if (vadUser != null) bufUser.take(trim) else null,
            partnerPcm = bufPartner.take(trim),
            speechMs = speechFrames * FRAME_MS,
            meanDiffDb = if (diffCount > 0) (diffSum / diffCount).toFloat() else null,
            endedAt = System.currentTimeMillis(),
        )
        resetSpeech()
        return segment.takeIf { it.speechMs >= minSpeechMs }
    }

    /** Uygulama kendi sesini çalarken girişi yok saymak için: yarım parçayı ve VAD durumunu sıfırlar. */
    fun reset() {
        resetSpeech()
        preUser.clear()
        prePartner.clear()
        vadUser?.reset()
        vadPartner.reset()
        lastProb = 0f
    }

    private fun resetSpeech() {
        inSpeech = false
        onsetCount = 0
        silentFrames = 0
        bufUser.clear()
        bufPartner.clear()
    }

    private fun push(q: ArrayDeque<ShortArray>, frame: ShortArray?) {
        if (frame == null) return
        q.addLast(frame)
        while (q.size > preRollFrames) q.removeFirst()
    }

    companion object {
        const val FRAME = 512
        const val FRAME_MS = 32
    }
}

/** Büyüyen 16-bit PCM arabelleği. */
internal class PcmBuffer {
    private val out = ByteArrayOutputStream()
    private var samples = 0

    val size: Int get() = samples

    fun append(frame: ShortArray) {
        for (v in frame) {
            out.write(v.toInt() and 0xff)
            out.write((v.toInt() shr 8) and 0xff)
        }
        samples += frame.size
    }

    /** İçeriği döndürür, sondan [dropSamples] örneği atar ve arabelleği boşaltır. */
    fun take(dropSamples: Int): ShortArray {
        val bytes = out.toByteArray()
        val n = (samples - dropSamples).coerceAtLeast(0)
        val pcm = ShortArray(n) { i -> ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() }
        clear()
        return pcm
    }

    fun clear() {
        out.reset()
        samples = 0
    }
}

