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

    /** Dünyada en çok konuşulan 10 dil + Avrupa ve Doğu Asya'dan 8 dil. */
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
        Lang(
            "de", "Almanca", "Deutsch", Locale.forLanguageTag("de-DE"), "Hallo, wo ist die Passkontrolle?",
            "Entschuldigung, es gab eine Unterbrechung. Könnten Sie das bitte wiederholen?", "Hier schreiben…",
        ),
        Lang(
            "it", "İtalyanca", "Italiano", Locale.forLanguageTag("it-IT"), "Buongiorno, dov'è il controllo passaporti?",
            "Scusi, c'è stata un'interruzione. Potrebbe ripetere, per favore?", "Scriva qui…",
        ),
        Lang(
            "nl", "Hollandaca", "Nederlands", Locale.forLanguageTag("nl-NL"), "Hallo, waar is de paspoortcontrole?",
            "Sorry, er was een onderbreking. Kunt u dat herhalen, alstublieft?", "Typ hier…",
        ),
        Lang(
            "pl", "Lehçe", "Polski", Locale.forLanguageTag("pl-PL"), "Dzień dobry, gdzie jest kontrola paszportowa?",
            "Przepraszam, nastąpiła przerwa. Czy mogliby państwo powtórzyć?", "Wpisz tutaj…",
        ),
        Lang(
            "cs", "Çekçe", "Čeština", Locale.forLanguageTag("cs-CZ"), "Dobrý den, kde je pasová kontrola?",
            "Promiňte, došlo k přerušení. Mohli byste to prosím zopakovat?", "Pište sem…",
        ),
        Lang(
            "uk", "Ukraynaca", "Українська", Locale.forLanguageTag("uk-UA"), "Добрий день, де паспортний контроль?",
            "Вибачте, стався збій. Чи не могли б ви повторити?", "Напишіть тут…",
        ),
        Lang(
            "ja", "Japonca", "日本語", Locale.forLanguageTag("ja-JP"), "こんにちは、パスポート検査はどこですか？",
            "すみません、途中で中断しました。もう一度言っていただけますか？", "ここに入力…",
        ),
        Lang(
            "ko", "Korece", "한국어", Locale.forLanguageTag("ko-KR"), "안녕하세요, 여권 심사는 어디인가요?",
            "죄송합니다, 중간에 끊겼습니다. 다시 한 번 말씀해 주시겠어요?", "여기에 입력…",
        ),
    )

    val ALL = listOf(TURKISH) + FOREIGN
}
