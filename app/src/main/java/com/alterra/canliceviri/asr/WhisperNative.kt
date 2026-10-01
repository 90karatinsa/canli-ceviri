package com.alterra.canliceviri.asr

/** whisper_jni.cpp'nin döndürdüğü ham sonuç. Alan sırası JNI'deki yapıcıyla aynı olmalı. */
class NativeTranscript(
    @JvmField val text: ByteArray,
    @JvmField val lang: String,
    @JvmField val totalMs: Float,
    @JvmField val encodeMs: Float,
    @JvmField val decodeMs: Float,
    @JvmField val noSpeechProb: Float,
    @JvmField val avgTokenP: Float,
    @JvmField val tokens: Int,
    /** Kod çözücü döngüye girdiyse metnin kesildiği belirteç (tekrar öncesi), yoksa -1. */
    @JvmField val loopAt: Int,
    @JvmField val rc: Int,
)

/** whisper.cpp'ye JNI köprüsü (libcanliceviri.so). */
object WhisperNative {
    init {
        System.loadLibrary("canliceviri")
    }

    external fun systemInfo(): String

    external fun init(modelPath: String, flashAttn: Boolean): Long

    external fun free(handle: Long)

    external fun transcribe(
        handle: Long,
        samples: FloatArray,
        language: String?,
        threads: Int,
        audioCtx: Int,
        beam: Int,
        maxTokens: Int,
        prompt: String?,
        temperatureInc: Float,
    ): NativeTranscript

    /**
     * Dönüş: [süre ms, aday0 olasılığı, aday1 olasılığı, …] ya da hata durumunda null. Ses [audioCtx] bağlamıyla
     * kodlanıp saklanır; hemen ardından aynı ses aynı bağlamla [transcribe] edilirse kodlayıcı yeniden çalışmaz.
     */
    external fun detectLanguage(handle: Long, samples: FloatArray, threads: Int, candidates: Array<String>, audioCtx: Int): FloatArray?

    external fun vadInit(modelPath: String, threads: Int): Long

    external fun vadProcess(handle: Long, frame: FloatArray): Float

    external fun vadReset(handle: Long)

    external fun vadFree(handle: Long)
}
