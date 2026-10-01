package com.alterra.canliceviri.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Küçük sinyal işleme yardımcıları. Seviyeler dBFS (tam ölçek = 0 dB). */
object Dsp {
    const val FLOOR_DB = -120f

    /** Çerçevenin ortalama gücü, tam ölçeğe göre (0..1). */
    fun meanPower(buf: ShortArray, n: Int = buf.size): Double {
        var sum = 0.0
        for (i in 0 until n) {
            val v = buf[i].toDouble()
            sum += v * v
        }
        return sum / n / (32768.0 * 32768.0)
    }

    fun powerToDb(p: Double): Float =
        if (p <= 1e-12) FLOOR_DB else (10.0 * log10(p)).toFloat().coerceAtLeast(FLOOR_DB)

    fun isAllZero(buf: ShortArray): Boolean {
        for (v in buf) if (v.toInt() != 0) return false
        return true
    }

    /** Goertzel: çerçevede verilen frekanstaki sinüsün genliği (dBFS). */
    fun goertzelDb(buf: ShortArray, sampleRate: Int, freq: Double): Float {
        val n = buf.size
        val k = (0.5 + n * freq / sampleRate).toInt()
        val coeff = 2.0 * cos(2.0 * PI * k / n)
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until n) {
            val s0 = buf[i] / 32768.0 + coeff * s1 - s2
            s2 = s1
            s1 = s0
        }
        val power = max(s1 * s1 + s2 * s2 - coeff * s1 * s2, 0.0)
        val amplitude = 2.0 * sqrt(power) / n
        return if (amplitude < 1e-9) FLOOR_DB else (20.0 * log10(amplitude)).toFloat()
    }

    /**
     * Bip dizisi: [beeps] adet [beepMs] ms bip, aralarında [gapMs] ms sessizlik.
     * Sürekli ton yerine bip kullanılıyor; telefonların gürültü bastırması sabit tonu zamanla yutabiliyor.
     */
    fun beeps(
        sampleRate: Int,
        freq: Double,
        amplitude: Double,
        beeps: Int = 5,
        beepMs: Int = 150,
        gapMs: Int = 100,
    ): ShortArray {
        val beepN = sampleRate * beepMs / 1000
        val gapN = sampleRate * gapMs / 1000
        val fade = sampleRate / 200 // 5 ms
        val out = ShortArray(beeps * (beepN + gapN))
        for (b in 0 until beeps) {
            val start = b * (beepN + gapN)
            for (i in 0 until beepN) {
                val env = when {
                    i < fade -> i / fade.toDouble()
                    i > beepN - fade -> (beepN - i) / fade.toDouble()
                    else -> 1.0
                }
                out[start + i] = (sin(2.0 * PI * freq * i / sampleRate) * amplitude * env * 32767.0).toInt().toShort()
            }
        }
        return out
    }

    fun percentile(values: List<Float>, q: Double): Float {
        if (values.isEmpty()) return FLOOR_DB
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    fun median(values: List<Float>): Float = percentile(values, 0.5)
}
