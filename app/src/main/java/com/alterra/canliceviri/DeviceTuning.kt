package com.alterra.canliceviri

import java.io.File

/** Telefona göre ayarlar. */
object DeviceTuning {

    /**
     * Whisper ve çeviri modeli için iş parçacığı sayısı: en yüksek frekansın %75'ine ulaşan (hızlı) çekirdekler.
     * Pixel 9 Pro'da 4 (3,1 + 3×2,6 GHz), S24 Ultra'da 6. Yavaş çekirdekleri katmak hızlandırmıyor, yavaşlatıyor.
     */
    val threads: Int by lazy {
        val freqs = (0 until 16).mapNotNull { cpu ->
            try {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            } catch (_: Exception) {
                null
            }
        }
        if (freqs.isEmpty()) return@lazy (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)
        val top = freqs.max()
        freqs.count { it >= top * 3 / 4 }.coerceIn(2, 8)
    }
}
