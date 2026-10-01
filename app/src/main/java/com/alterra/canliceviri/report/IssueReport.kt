package com.alterra.canliceviri.report

import android.content.Context
import com.alterra.canliceviri.BuildConfig
import com.alterra.canliceviri.Languages
import com.alterra.canliceviri.pipeline.ConversationEngine
import com.alterra.canliceviri.pipeline.Utterance
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sorun bildirimi: son konuşmalar, kararlar, kesintiler, ayarlar ve (isteğe bağlı) son parçaların ses kaydı.
 * Dosyalar `files/sorun/` altında; FileProvider ile paylaşılır.
 */
object IssueReport {

    class Bundle(val report: File, val attachments: List<File>, val summary: String)

    class Settings(
        val mode: String,
        val manualInput: Boolean,
        val partnerLang: String?,
        val speechRate: Float,
        val attachAudio: Boolean,
        val ttsEngine: String?,
        val missingVoices: List<String>,
    )

    fun dir(context: Context): File = File(context.getExternalFilesDir(null), "sorun").apply { mkdirs() }

    /** Raporu yazar; [attachAudio] ise son parçaların WAV'larını da ekler. */
    fun build(context: Context, engine: ConversationEngine, settings: Settings, userNote: String, attachAudio: Boolean): Bundle {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val status = engine.status.value
        val utterances = engine.utterances.value.takeLast(30)
        val audio = if (attachAudio) engine.recentSegmentFiles() else emptyList()

        val text = buildString {
            appendLine("Canlı Çeviri · Sorun bildirimi · sürüm ${BuildConfig.VERSION_NAME}")
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
            appendLine(DeviceInfo.summary(context))
            DeviceInfo.lastExit(context)?.let { appendLine("Önceki kapanış: $it") }
            appendLine()
            appendLine("NE OLDU")
            appendLine(userNote.ifBlank { "(yazılmadı)" })
            appendLine()
            appendLine("AYARLAR")
            appendLine("Mod: ${settings.mode} · konuşma biçimi: ${if (settings.manualInput) "düğmeyle" else "otomatik"} · " +
                "karşı dil: ${settings.partnerLang ?: "otomatik"} · okuma hızı: ${settings.speechRate}")
            appendLine("Ses motoru: ${settings.ttsEngine ?: "?"} · eksik sesler: ${settings.missingVoices.ifEmpty { listOf("yok") }.joinToString()}")
            appendLine("Durum: çalışıyor=${status.running} · mikrofon=${status.micMode} (${status.micInfo}) · duraklama=${status.paused ?: "yok"} · " +
                "kulaklık=${if (status.headsetAttached) "takılı" else "yok"} · medya sesi=%${(status.speakerVolume * 100).toInt()} · " +
                "karşı dil (tespit)=${status.partnerLang ?: "-"}${if (status.partnerLangLocked) " kilitli" else ""} · hata=${status.error ?: "yok"}")
            appendLine()
            appendLine("SON KONUŞMALAR (${utterances.size})")
            utterances.forEach { appendLine(format(it)) }
            appendLine()
            appendLine("OLAYLAR")
            engine.recentEvents().takeLast(120).forEach { appendLine(it) }
            appendLine()
            appendLine("EKLER (${audio.size})")
            audio.forEach { appendLine("${it.name} (${it.length() / 1024} KB)") }
        }
        val report = File(dir(context), "rapor-$stamp.txt")
        report.writeText(text)
        prune(context)
        val summary = "Canlı Çeviri sorun bildirimi ($stamp): ${userNote.ifBlank { "açıklama yok" }} · ${utterances.size} konuşma, ${audio.size} ses"
        return Bundle(report, audio, summary)
    }

    private fun format(u: Utterance): String {
        val name = { code: String? -> code?.let { c -> Languages.ALL.firstOrNull { it.code == c }?.nameTr ?: c } ?: "-" }
        return "#${u.id} ${if (u.speaker.name == "USER") "Siz" else "Karşı"} ${name(u.sourceLang)}→${name(u.targetLang)} ${u.state}" +
            (if (u.typed) " yazılı" else " ses ${u.audioMs} ms") +
            " yazı=${u.asrMs ?: "-"} ms çeviri=${u.mtMs ?: "-"} ms ilk-ses=${u.latencyMs ?: "-"} ms | ${u.text ?: ""} | ${u.translation ?: ""}" +
            (u.note?.let { " | $it" } ?: "")
    }

    /** Klasör şişmesin: en yeni 5 rapor ve en yeni 20 parça kalır. */
    private fun prune(context: Context) {
        val files = dir(context).listFiles() ?: return
        files.filter { it.name.startsWith("rapor-") }.sortedByDescending { it.lastModified() }.drop(5).forEach { it.delete() }
        files.filter { it.name.startsWith("parca-") }.sortedByDescending { it.lastModified() }.drop(20).forEach { it.delete() }
    }
}
