package com.alterra.canliceviri

import java.util.Locale

/** Uygulamanın dillerinden biri. Kullanıcının dili her zaman Türkçe. */
data class Lang(
    /** ISO 639-1 kodu; Whisper ve çeviri modeli aynı kodları kullanıyor. */
    val code: String,
    val nameTr: String,
    val nativeName: String,
    val ttsLocale: Locale,
    /** Seslendirme testinde okunan örnek cümle. */
    val sample: String,
)

object Languages {
    val TURKISH = Lang("tr", "Türkçe", "Türkçe", Locale.forLanguageTag("tr-TR"), "Merhaba, pasaport kontrolü nerede?")

    /** Dünyada en çok konuşulan 10 dil. */
    val FOREIGN = listOf(
        Lang("en", "İngilizce", "English", Locale.forLanguageTag("en-US"), "Hello, where is passport control?"),
        Lang("zh", "Çince", "中文", Locale.forLanguageTag("zh-CN"), "你好，护照检查在哪里？"),
        Lang("hi", "Hintçe", "हिन्दी", Locale.forLanguageTag("hi-IN"), "नमस्ते, पासपोर्ट जांच कहाँ है?"),
        Lang("es", "İspanyolca", "Español", Locale.forLanguageTag("es-ES"), "Hola, ¿dónde está el control de pasaportes?"),
        Lang("ar", "Arapça", "العربية", Locale.forLanguageTag("ar"), "مرحبا، أين مراقبة الجوازات؟"),
        Lang("fr", "Fransızca", "Français", Locale.forLanguageTag("fr-FR"), "Bonjour, où se trouve le contrôle des passeports ?"),
        Lang("bn", "Bengalce", "বাংলা", Locale.forLanguageTag("bn-BD"), "হ্যালো, পাসপোর্ট চেক কোথায়?"),
        Lang("pt", "Portekizce", "Português", Locale.forLanguageTag("pt-BR"), "Olá, onde fica o controle de passaportes?"),
        Lang("ru", "Rusça", "Русский", Locale.forLanguageTag("ru-RU"), "Здравствуйте, где паспортный контроль?"),
        Lang("id", "Endonezce", "Bahasa Indonesia", Locale.forLanguageTag("id-ID"), "Halo, di mana pemeriksaan paspor?"),
    )

    val ALL = listOf(TURKISH) + FOREIGN
}
