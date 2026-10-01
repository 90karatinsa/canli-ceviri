package com.alterra.canliceviri.llm

import java.io.Closeable
import java.io.File

/** hymt_jni.cpp'nin döndürdüğü ham sonuç; alan sırası JNI'deki yapıcıyla aynı olmalı. */
class LlmResult(
    @JvmField val text: ByteArray,
    @JvmField val promptTokens: Int,
    @JvmField val genTokens: Int,
    @JvmField val promptMs: Float,
    @JvmField val genMs: Float,
    @JvmField val rc: Int,
)

/** llama.cpp'ye JNI köprüsü (libhymt.so). */
object LlamaNative {
    init {
        System.loadLibrary("hymt")
    }

    external fun systemInfo(): String

    external fun load(modelPath: String, threads: Int, nCtx: Int): Long

    external fun generate(handle: Long, userMessage: ByteArray, maxTokens: Int): LlmResult

    external fun free(handle: Long)
}

/**
 * Tencent Hy-MT2 çeviri modeli (1.8B, GGUF), telefonun işlemcisinde.
 * 33 dil; bu uygulamanın 11 dilinin hepsi dahil. İstem biçimi modelin belgesindeki "varsayılan çeviri" istemi.
 */
class HyMtTranslator private constructor(private var handle: Long, val file: File) : Closeable {

    data class Result(
        val text: String,
        val promptTokens: Int,
        val genTokens: Int,
        val promptMs: Float,
        val genMs: Float,
        val ok: Boolean,
    ) {
        val totalMs: Float get() = promptMs + genMs
    }

    fun translate(text: String, target: String): Result {
        check(handle != 0L) { "çeviri modeli kapalı" }
        val name = LANGUAGE_NAMES[target] ?: throw IllegalArgumentException("Desteklenmeyen hedef dil: $target")
        val message = "Translate the following text into $name. Note that you should only output the translated result " +
            "without any additional explanation:\n\n$text"
        // Çeviri kaynak metinden çok uzun olmaz; model takılırsa sonsuza kadar üretmesin.
        val maxTokens = (text.length * 2 + 16).coerceIn(32, 384)
        val r = LlamaNative.generate(handle, message.toByteArray(Charsets.UTF_8), maxTokens)
        return Result(
            text = r.text.toString(Charsets.UTF_8).trim(),
            promptTokens = r.promptTokens,
            genTokens = r.genTokens,
            promptMs = r.promptMs,
            genMs = r.genMs,
            ok = r.rc == 0,
        )
    }

    override fun close() {
        if (handle != 0L) {
            LlamaNative.free(handle)
            handle = 0
        }
    }

    companion object {
        const val MODEL_FILE = "Hy-MT2-1.8B-Q4_K_M.gguf"
        const val MODEL_URL = "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/main/Hy-MT2-1.8B-Q4_K_M.gguf"

        /** Kaliteli (yavaş) çeviri: aynı ailenin 7B modeli, isteğe bağlı indirilir (4,6 GB). */
        const val QUALITY_MODEL_FILE = "Hy-MT2-7B-Q4_K_M.gguf"
        const val QUALITY_MODEL_URL = "https://huggingface.co/tencent/Hy-MT2-7B-GGUF/resolve/main/Hy-MT2-7B-Q4_K_M.gguf"

        /** Modelin belgesine göre istemde tam İngilizce dil adları kullanılmalı. */
        val LANGUAGE_NAMES = mapOf(
            "tr" to "Turkish", "en" to "English", "zh" to "Chinese", "hi" to "Hindi", "es" to "Spanish",
            "ar" to "Arabic", "fr" to "French", "bn" to "Bengali", "pt" to "Portuguese", "ru" to "Russian",
            "id" to "Indonesian", "de" to "German", "it" to "Italian", "nl" to "Dutch", "pl" to "Polish",
            "cs" to "Czech", "uk" to "Ukrainian", "ja" to "Japanese", "ko" to "Korean",
        )

        fun load(file: File, threads: Int = 4, nCtx: Int = 1024): HyMtTranslator? {
            if (!file.exists()) return null
            val h = LlamaNative.load(file.absolutePath, threads, nCtx)
            return if (h == 0L) null else HyMtTranslator(h, file)
        }
    }
}
