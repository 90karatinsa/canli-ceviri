package com.alterra.canliceviri.dev

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.alterra.canliceviri.Languages
import com.alterra.canliceviri.llm.HyMtTranslator
import com.alterra.canliceviri.llm.LlamaNative
import java.io.File
import java.util.Locale

/**
 * Geliştirici çeviri testi (Hy-MT2): test cümlelerini Türkçeye, Türkçe cümleleri her dile çevirip süreleri ölçer.
 * Sonuç files/mt.txt. Tetikleme: adb shell am start --activity-single-top -n com.alterra.canliceviri/.MainActivity --es mt "threads=4"
 */
object MtBench {
    private const val TAG = "CanliCeviriBench"

    private val TURKISH_SAMPLES = listOf(
        "Merhaba, pasaport kontrolü nerede acaba?",
        "Uçağım üçte kalkıyor ama bagajımı kaybettim, bana yardım eder misiniz?",
    )

    fun run(context: Context, spec: String, progress: (String) -> Unit): String {
        val out = StringBuilder()
        fun emit(line: String) {
            out.appendLine(line)
            Log.i(TAG, line)
            progress(line)
        }
        val threads = Regex("threads=(\\d+)").find(spec)?.groupValues?.get(1)?.toIntOrNull() ?: com.alterra.canliceviri.DeviceTuning.threads
        val base = context.getExternalFilesDir(null) ?: return "dosya dizini yok"
        val file = File(base, "models/${HyMtTranslator.MODEL_FILE}")
        emit("Çeviri testi · ${file.name} · iş parçacığı $threads")
        emit("CPU: ${LlamaNative.systemInfo().trim()}")
        val t0 = SystemClock.elapsedRealtime()
        val mt = HyMtTranslator.load(file, threads)
        if (mt == null) {
            emit("model açılamadı: ${file.path}")
            return out.toString()
        }
        emit("yükleme ${SystemClock.elapsedRealtime() - t0} ms")
        mt.use { m ->
            m.translate("Hello", "tr") // ısınma
            val manifest = File(base, "test/manifest.tsv")
            val refs = if (manifest.exists()) manifest.readLines().map { it.split('\t') }.filter { it.size >= 3 && it[1] != "tr" } else emptyList()
            var sum = 0f
            var n = 0
            emit("== Yabancı → Türkçe")
            for (r in refs) {
                val res = m.translate(r[2], "tr")
                sum += res.totalMs; n++
                emit(String.format(Locale.US, "%s→tr %5.0f ms (istem %3d/%4.0f ms, üretim %3d/%5.0f ms) | %s", r[1], res.totalMs, res.promptTokens, res.promptMs, res.genTokens, res.genMs, res.text))
            }
            emit("== Türkçe → yabancı")
            for (lang in Languages.FOREIGN.map { it.code }) for (text in TURKISH_SAMPLES) {
                val res = m.translate(text, lang)
                sum += res.totalMs; n++
                emit(String.format(Locale.US, "tr→%s %5.0f ms (istem %3d/%4.0f ms, üretim %3d/%5.0f ms) | %s", lang, res.totalMs, res.promptTokens, res.promptMs, res.genTokens, res.genMs, res.text))
            }
            emit(String.format(Locale.US, "Ortalama %.0f ms / cümle (%d cümle)", sum / n, n))
        }
        File(base, "mt.txt").writeText(out.toString())
        return out.toString()
    }
}
