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
    /** Kesintiden sonra karşı taraftan tekrar istemek için okunan cümle (sabit; çeviri modeline bırakılmıyor). */
    val repeatRequest: String,
    /** Yazı kutusunun ipucu ("Buraya yazın"). */
    val typeHint: String,
)

object Languages {
    val TURKISH = Lang(
        "tr", "Türkçe", "Türkçe", Locale.forLanguageTag("tr-TR"), "Merhaba, pasaport kontrolü nerede?",
        "Bir kesinti oldu, lütfen tekrar söyler misiniz?", "Türkçe yazın…",
    )

    /** Dünyada en çok konuşulan 10 dil. */
    val FOREIGN = listOf(
        Lang(
            "en", "İngilizce", "English", Locale.forLanguageTag("en-US"), "Hello, where is passport control?",
            "Sorry, there was an interruption. Could you please repeat that?", "Type here…",
        ),
        Lang(
            "zh", "Çince", "中文", Locale.forLanguageTag("zh-CN"), "你好，护照检查在哪里？",
            "抱歉，刚才有干扰，请再说一遍好吗？", "请在此输入…",
        ),
        Lang(
            "hi", "Hintçe", "हिन्दी", Locale.forLanguageTag("hi-IN"), "नमस्ते, पासपोर्ट जांच कहाँ है?",
            "माफ़ कीजिए, बीच में रुकावट आ गई। क्या आप दोबारा कह सकते हैं?", "यहाँ लिखें…",
        ),
        Lang(
            "es", "İspanyolca", "Español", Locale.forLanguageTag("es-ES"), "Hola, ¿dónde está el control de pasaportes?",
            "Perdón, hubo una interrupción. ¿Podría repetirlo, por favor?", "Escriba aquí…",
        ),
        Lang(
            "ar", "Arapça", "العربية", Locale.forLanguageTag("ar"), "مرحبا، أين مراقبة الجوازات؟",
            "عذرًا، حدث انقطاع. هل يمكنك التكرار من فضلك؟", "اكتب هنا…",
        ),
        Lang(
            "fr", "Fransızca", "Français", Locale.forLanguageTag("fr-FR"), "Bonjour, où se trouve le contrôle des passeports ?",
            "Pardon, il y a eu une interruption. Pouvez-vous répéter, s'il vous plaît ?", "Écrivez ici…",
        ),
        Lang(
            "bn", "Bengalce", "বাংলা", Locale.forLanguageTag("bn-BD"), "হ্যালো, পাসপোর্ট চেক কোথায়?",
            "দুঃখিত, একটু বাধা পড়েছিল। আবার বলবেন কি?", "এখানে লিখুন…",
        ),
        Lang(
            "pt", "Portekizce", "Português", Locale.forLanguageTag("pt-BR"), "Olá, onde fica o controle de passaportes?",
            "Desculpe, houve uma interrupção. Pode repetir, por favor?", "Escreva aqui…",
        ),
        Lang(
            "ru", "Rusça", "Русский", Locale.forLanguageTag("ru-RU"), "Здравствуйте, где паспортный контроль?",
            "Извините, произошёл сбой. Не могли бы вы повторить?", "Напишите здесь…",
        ),
        Lang(
            "id", "Endonezce", "Bahasa Indonesia", Locale.forLanguageTag("id-ID"), "Halo, di mana pemeriksaan paspor?",
            "Maaf, tadi ada gangguan. Bisa diulangi?", "Ketik di sini…",
        ),
    )

    val ALL = listOf(TURKISH) + FOREIGN
}
