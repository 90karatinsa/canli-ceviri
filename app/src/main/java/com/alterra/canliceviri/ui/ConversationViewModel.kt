package com.alterra.canliceviri.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alterra.canliceviri.asr.toFloatPcm
import com.alterra.canliceviri.audio.WavReader
import com.alterra.canliceviri.llm.HyMtTranslator
import com.alterra.canliceviri.pipeline.ConversationEngine
import com.alterra.canliceviri.pipeline.Mode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class SetupState(
    val missingModels: List<String> = emptyList(),
    /** İnternetsiz sesi olmayan diller (Türkçe adlarıyla). */
    val missingVoices: List<String> = emptyList(),
    val modelProgress: String? = null,
    val missingPacks: List<String> = emptyList(),
    val packProgress: String? = null,
    /** Google'ın ses motoru kurulu değil (Play Store'dan kurulmalı). */
    val googleTtsMissing: Boolean = false,
    val checked: Boolean = false,
) {
    val ready: Boolean get() = checked && missingModels.isEmpty() && missingPacks.isEmpty() && missingVoices.isEmpty()
}

class ConversationViewModel(app: Application) : AndroidViewModel(app) {

    val engine = ConversationEngine(app, viewModelScope)
    val status = engine.status
    val utterances = engine.utterances

    var mode by mutableStateOf(Mode.CONVERSATION); private set
    var partnerLang by mutableStateOf<String?>(null); private set
    var setup by mutableStateOf(SetupState()); private set
    var starting by mutableStateOf(false); private set

    init {
        refreshSetup()
    }

    fun toggle() {
        if (status.value.running) {
            engine.stop()
            return
        }
        start()
    }

    private fun start() {
        if (starting) return
        starting = true
        viewModelScope.launch {
            try {
                engine.start(ConversationEngine.Settings(mode = mode))
            } finally {
                starting = false
            }
        }
    }

    fun selectMode(m: Mode) {
        if (m == mode) return
        mode = m
        if (status.value.running) {
            engine.stop()
            start()
        }
    }

    fun selectPartnerLang(code: String?) {
        partnerLang = code
        engine.setPartnerLanguage(code)
    }

    fun clear() = engine.clearConversation()

    fun refreshSetup() {
        setup = setup.copy(missingModels = engine.missingModels(), missingPacks = emptyList(), checked = true)
        viewModelScope.launch {
            val missing = engine.voiceStatuses()
                .filter { it.state != com.alterra.canliceviri.audio.TtsEngine.State.READY }
                .map { it.lang.nameTr }
            setup = setup.copy(missingVoices = missing, googleTtsMissing = engine.googleTtsMissing)
        }
    }

    val ttsEnginePackage: String? get() = engine.ttsEnginePackage

    /** Konuşma tanıma, ses algılama ve çeviri modellerini Hugging Face'ten indirir (bir kez, ~1,4 GB). */
    fun downloadModels() {
        if (setup.modelProgress != null) return
        viewModelScope.launch {
            val dir = engine.modelsDir.apply { mkdirs() }
            for (name in setup.missingModels) {
                val url = MODEL_URLS[name] ?: continue
                try {
                    withContext(Dispatchers.IO) {
                        download(url, File(dir, name)) { done, total ->
                            val text = if (total > 0) "$name: %${100 * done / total} (${done / 1_000_000}/${total / 1_000_000} MB)" else "$name: ${done / 1_000_000} MB"
                            viewModelScope.launch { setup = setup.copy(modelProgress = text) }
                        }
                    }
                } catch (e: Exception) {
                    setup = setup.copy(modelProgress = "İndirilemedi: $name (${e.message}). İnternet bağlantısını ve uygulamanın ağ iznini kontrol edin.")
                    return@launch
                }
            }
            setup = setup.copy(modelProgress = null)
            refreshSetup()
        }
    }

    /**
     * Geliştirici: test seslerini mikrofon yerine motora verir (konuşmadan uçtan uca ölçüm).
     * adb shell am start --activity-single-top -n com.alterra.canliceviri/.MainActivity --es simule "tr1,en,tr3"
     * "cift:" ile başlarsa çift mikrofon; "karsi=bn;bn,tr1" karşı tarafın dilini elle seçer.
     */
    fun simulate(spec: String) {
        viewModelScope.launch {
            val parts = spec.split(';').map { it.trim() }
            val options = parts.dropLast(1).mapNotNull { o -> o.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val list = parts.last()
            engine.setPartnerLanguage(options["karsi"])
            val dual = list.startsWith("cift:")
            val names = list.removePrefix("cift:").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val dir = File(getApplication<Application>().getExternalFilesDir(null), "test")
            val clips = names.mapNotNull { name ->
                val file = File(dir, if (name.endsWith(".wav")) name else "$name.wav")
                WavReader.read(file)?.samples?.let { ConversationEngine.SimClip(it.toFloatPcm(), isUser = name.startsWith("tr")) }
            }
            if (clips.isEmpty()) return@launch
            engine.simulate(clips, ConversationEngine.Settings(mode = mode), dual)
        }
    }

    override fun onCleared() {
        engine.release()
    }

    /** İndirir; bağlantı koparsa bir sonraki denemede kaldığı yerden devam eder (.part dosyası + Range). */
    private fun download(url: String, target: File, progress: (Long, Long) -> Unit) {
        val part = File(target.path + ".part")
        var attempt = 0
        while (true) {
            try {
                downloadOnce(url, part, progress)
                break
            } catch (e: java.io.IOException) {
                // Mobil ağda kopmalar olağan: birkaç kez kaldığı yerden yeniden dene.
                if (++attempt >= 5) throw e
                Thread.sleep(2000L * attempt)
            }
        }
        if (!part.renameTo(target)) throw IllegalStateException("dosya taşınamadı")
    }

    private fun downloadOnce(url: String, part: File, progress: (Long, Long) -> Unit) {
        val have = if (part.exists()) part.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
        conn.connect()
        val code = conn.responseCode
        if (code == 416) return // zaten tamamı inmiş
        if (code !in 200..299) throw java.io.IOException("HTTP $code")
        val resumed = code == 206
        val total = (if (resumed) have else 0L) + conn.contentLengthLong
        var done = if (resumed) have else 0L
        var lastReport = 0L
        conn.inputStream.use { input ->
            java.io.FileOutputStream(part, resumed).use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - lastReport > 2_000_000) {
                        lastReport = done
                        progress(done, total)
                    }
                }
            }
        }
        if (total > 0 && done != total) throw java.io.IOException("eksik indi ($done/$total)")
    }

    companion object {
        val MODEL_URLS = mapOf(
            ConversationEngine.DEFAULT_ASR_MODEL to "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q8_0.bin",
            ConversationEngine.VAD_MODEL to "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v6.2.0.bin",
            HyMtTranslator.MODEL_FILE to HyMtTranslator.MODEL_URL,
        )
    }
}
