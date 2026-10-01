package com.alterra.canliceviri.pipeline

/** Konuşma: iki yönlü çeviri. Dinleme: duyulan her şey Türkçeye çevrilip kulaklığa verilir (anons, uzun anlatım). */
enum class Mode { CONVERSATION, LISTENING }

enum class Speaker { USER, PARTNER }

enum class MicMode {
    /** Kulaklık mikrofonu (kullanıcı) + telefon mikrofonu (karşı taraf) aynı anda. */
    DUAL,

    /** Tek mikrofon; kimin konuştuğu dilden anlaşılıyor (Türkçe mi, değil mi). */
    SINGLE,
    NONE,
}

enum class UtteranceState { TRANSCRIBING, TRANSLATING, SPEAKING, DONE, DROPPED, ERROR }

/** Ekranda gösterilen bir konuşma parçası. */
data class Utterance(
    val id: Long,
    val speaker: Speaker,
    val state: UtteranceState,
    val audioMs: Long,
    val sourceLang: String? = null,
    val text: String? = null,
    val targetLang: String? = null,
    val translation: String? = null,
    /** Konuşma bitişinden (sessizlik algılandıktan sonra) yazının çıkmasına kadar. */
    val asrMs: Long? = null,
    val mtMs: Long? = null,
    /** Konuşma bitişinden çevirinin ilk sesine kadar geçen toplam süre. */
    val latencyMs: Long? = null,
    /** Kullanıcıya gösterilen not (düzeltildi, kaliteli çeviri, okunamadı…). */
    val note: String? = null,
    /** Teknik not (dil tespiti, döngü); yalnızca geliştirici bilgileri açıkken ve raporda. */
    val detail: String? = null,
    /** Konuşma yerine yazılarak girildi. */
    val typed: Boolean = false,
    /** Kaliteli (7B) modelle çevrildi. */
    val quality: Boolean = false,
)

data class EngineStatus(
    val running: Boolean = false,
    /** Modeller yükleniyor, mikrofon açılıyor (Başlat'a basıldı, henüz dinlemiyor). */
    val starting: Boolean = false,
    /** Dinleme geçici olarak durdu (arama vb.); null değilse nedeni. [running] bu sırada true kalır. */
    val paused: String? = null,
    val micMode: MicMode = MicMode.NONE,
    val micInfo: String = "",
    val levelUser: Float = -120f,
    val levelPartner: Float = -120f,
    /** Şu an konuşma algılanıyor mu, algılanıyorsa (çift mikrofonda) kim? */
    val hearing: Speaker? = null,
    val hearingAnyone: Boolean = false,
    val playing: Boolean = false,
    val busy: Boolean = false,
    val partnerLang: String? = null,
    val partnerLangLocked: Boolean = false,
    /** Bas-konuş: düğme basılı, bu konuşan kaydediliyor. */
    val recording: Speaker? = null,
    val recordingSince: Long = 0L,
    /** Medya ses düzeyi (0..1); kulaklık takılıysa kulaklığınki. */
    val speakerVolume: Float = 1f,
    val headsetAttached: Boolean = false,
    val error: String? = null,
)
