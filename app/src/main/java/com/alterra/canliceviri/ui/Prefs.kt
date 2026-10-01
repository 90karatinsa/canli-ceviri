package com.alterra.canliceviri.ui

import android.content.Context
import android.content.SharedPreferences

/** Kalıcı kullanıcı ayarları (küçük, senkron). */
class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)

    /** Konuşma biçimi: otomatik algılama ya da basılı tutulan düğmeler. */
    var manualInput: Boolean
        get() = p.getBoolean("dugmeyle", false)
        set(v) = p.edit().putBoolean("dugmeyle", v).apply()

    /** Seslendirme hızı (1,0 normal; 0,8 yavaş). */
    var speechRate: Float
        get() = p.getFloat("okuma_hizi", 1f)
        set(v) = p.edit().putFloat("okuma_hizi", v).apply()

    /** Sorun bildirimine son konuşmaların ses kaydı eklensin mi. */
    var attachAudio: Boolean
        get() = p.getBoolean("ses_ekle", true)
        set(v) = p.edit().putBoolean("ses_ekle", v).apply()

    /** Elle seçilmiş karşı taraf dili (null: otomatik). */
    var partnerLang: String?
        get() = p.getString("karsi_dil", null)
        set(v) = p.edit().putString("karsi_dil", v).apply()

    /** Kaliteli (7B) çeviri modu. */
    var quality: Boolean
        get() = p.getBoolean("kaliteli", false)
        set(v) = p.edit().putBoolean("kaliteli", v).apply()

    /** Geliştirici bilgileri: süreler, dil tespiti, seviye çubukları ekranda görünsün. */
    var devInfo: Boolean
        get() = p.getBoolean("gelistirici", false)
        set(v) = p.edit().putBoolean("gelistirici", v).apply()

    /** Pil kısıtlaması penceresi bir kez gösterildi. */
    var batteryAsked: Boolean
        get() = p.getBoolean("pil_soruldu", false)
        set(v) = p.edit().putBoolean("pil_soruldu", v).apply()

    /** Bildirim izni bir kez istendi. */
    var notificationsAsked: Boolean
        get() = p.getBoolean("bildirim_soruldu", false)
        set(v) = p.edit().putBoolean("bildirim_soruldu", v).apply()
}
