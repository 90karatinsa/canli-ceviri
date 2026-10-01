package com.alterra.canliceviri.pipeline

import java.util.Locale

/** Whisper çıktısını temizleme: uydurma kalıp cümleler ve tekrar döngüleri. */
object TextFilters {

    /**
     * Whisper'ın sessizlik ya da gürültüde uydurduğu, altyazı jeneriklerinden kalma cümleler.
     * Karşılaştırma küçük harf, noktalamasız yapılıyor.
     */
    private val HALLUCINATIONS = setOf(
        // Türkçe
        "altyazı mk", "altyazı m k", "izlediğiniz için teşekkürler", "izlediğiniz için teşekkür ederim",
        "abone olmayı unutmayın", "altyazılar amaraorg topluluğu tarafından", "teşekkürler", "teşekkür ederim",
        "hoşça kalın", "görüşmek üzere",
        // İngilizce
        "thank you", "thanks for watching", "thank you for watching", "please subscribe", "bye", "you",
        "subtitles by the amaraorg community", "thanks", "okay",
        // Diğer
        "字幕由amaraorg社区提供", "请不吝点赞 订阅 转发 打赏支持明镜与点点栏目", "谢谢观看", "谢谢",
        "продолжение следует", "субтитры сделал dimatorzok", "спасибо за просмотр", "спасибо",
        "اشتركوا في القناة", "شكرا للمشاهدة", "شكرا",
        "subtítulos realizados por la comunidad de amaraorg", "gracias por ver", "gracias",
        "soustitres réalisés para la communauté damaraorg", "merci davoir regardé", "merci",
        "legendas pela comunidade amaraorg", "obrigado por assistir", "obrigado",
        "terima kasih telah menonton", "terima kasih",
        "धन्यवाद", "ধন্যবাদ",
    )

    fun normalize(text: String, lang: String?): String {
        val locale = if (lang != null) Locale.forLanguageTag(lang) else Locale.ROOT
        return text.lowercase(locale).replace(Regex("[\\p{P}\\p{S}]"), "").replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Uydurma sayılacak mı? Model konuşma olmadığından eminse ya da kalıp cümle düşük güvenle çıktıysa.
     * Seyahatte gerçek bir "thank you" çok sık geçtiği için kalıp cümleler yalnızca güven düşükken atılıyor.
     */
    fun isHallucination(text: String, lang: String?, noSpeechProb: Float, avgTokenP: Float): Boolean {
        val n = normalize(text, lang)
        if (n.isEmpty()) return true
        if (noSpeechProb > 0.6f && avgTokenP < 0.5f) return true
        if (n in HALLUCINATIONS && avgTokenP < 0.6f) return true
        if (n.contains("amaraorg")) return true
        return false
    }

    /** Aynı cümle art arda tekrarlanıyorsa (kod çözücü döngüsü) tek kopyasını bırakır. */
    fun collapseRepeats(text: String): String {
        val parts = Regex("(?<=[.!?。？！])\\s*").split(text.trim()).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return collapseWordRepeats(text.trim())
        val seen = LinkedHashSet<String>()
        val out = ArrayList<String>()
        for (p in parts) {
            val key = normalize(p, null)
            if (key.isEmpty() || seen.add(key)) out += p
        }
        return collapseWordRepeats(out.joinToString(" "))
    }

    /** Bengal alfabesinde karşılığı +0x80'de olmayan Devanagari harfleri: en yakın Bengal harfi. */
    private val DEVANAGARI_TO_BENGALI = mapOf(
        0x0904 to 0x0985, 0x090D to 0x098F, 0x090E to 0x098F, 0x0911 to 0x0993, 0x0912 to 0x0993,
        0x0929 to 0x09A8, 0x0931 to 0x09B0, 0x0933 to 0x09B2, 0x0934 to 0x09B2, 0x0935 to 0x09AC,
        0x0945 to 0x09C7, 0x0946 to 0x09C7, 0x0949 to 0x09CB, 0x094A to 0x09CB, 0x094F to 0x09CC,
        0x0958 to 0x0995, 0x0959 to 0x0996, 0x095A to 0x0997, 0x095B to 0x099C, 0x095E to 0x09AB,
    )

    /**
     * Whisper small Bengalce konuşmayı doğru duyup çoğu zaman Devanagari (Hint) harfleriyle yazıyor.
     * İki alfabe Unicode'da harf harf paralel (fark 0x80); Bengal harflerine çevrilince metin okunur hale geliyor.
     */
    fun devanagariToBengali(text: String): String {
        if (text.none { it in '\u0900'..'\u097F' }) return text
        val out = StringBuilder(text.length)
        text.codePoints().forEach { c ->
            val mapped = DEVANAGARI_TO_BENGALI[c] ?: when (c) {
                in 0x0901..0x0963, in 0x0966..0x096F -> (c + 0x80).takeIf { Character.isDefined(it) } ?: c
                else -> c // danda (।) ortak; diğerleri çok seyrek
            }
            out.appendCodePoint(mapped)
        }
        return out.toString()
    }

    /** "please please please please" gibi kelime döngülerini keser. */
    private fun collapseWordRepeats(text: String): String {
        val words = text.split(' ')
        if (words.size < 6) return text
        val out = ArrayList<String>()
        var run = 0
        for (w in words) {
            if (out.isNotEmpty() && normalize(out.last(), null) == normalize(w, null)) {
                run++
                if (run >= 2) continue
            } else {
                run = 0
            }
            out += w
        }
        return out.joinToString(" ")
    }
}
