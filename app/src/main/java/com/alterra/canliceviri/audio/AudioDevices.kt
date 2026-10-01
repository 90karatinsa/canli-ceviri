package com.alterra.canliceviri.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MicrophoneInfo
import android.os.Build

/** Ses cihazlarını bulma ve Türkçe adlandırma. */
object AudioDevices {

    /** AudioDeviceInfo.TYPE_ECHO_REFERENCE sistem API'si; uygulamalara açık değil ama listede görünüyor. */
    private const val TYPE_ECHO_REFERENCE = 28

    fun audioManager(context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun inputs(context: Context): List<AudioDeviceInfo> =
        audioManager(context).getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

    fun outputs(context: Context): List<AudioDeviceInfo> =
        audioManager(context).getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

    /** Kulaklık tarafındaki mikrofonlar, tercih sırasına göre (kablolu önce). */
    private val HEADSET_INPUT_PRIORITY = listOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    )

    /** Kulaklık tarafındaki çıkışlar, tercih sırasına göre. */
    private val HEADSET_OUTPUT_PRIORITY = listOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    )

    fun isHeadsetInput(d: AudioDeviceInfo) = d.type in HEADSET_INPUT_PRIORITY

    /** Konuşma kaydına uygun girişler (telefon hattı, sanal girişler vb. hariç). */
    fun isMic(d: AudioDeviceInfo) = d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC || isHeadsetInput(d)

    fun mics(context: Context): List<AudioDeviceInfo> = inputs(context).filter(::isMic)
    fun isHeadsetOutput(d: AudioDeviceInfo) = d.type in HEADSET_OUTPUT_PRIORITY

    fun builtinMic(context: Context): AudioDeviceInfo? =
        inputs(context).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    fun headsetMic(context: Context): AudioDeviceInfo? =
        inputs(context).filter(::isHeadsetInput).minByOrNull { HEADSET_INPUT_PRIORITY.indexOf(it.type) }

    fun speaker(context: Context): AudioDeviceInfo? =
        outputs(context).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    fun headsetOutput(context: Context): AudioDeviceInfo? =
        outputs(context).filter(::isHeadsetOutput).minByOrNull { HEADSET_OUTPUT_PRIORITY.indexOf(it.type) }

    fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Telefon mikrofonu"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Telefon hoparlörü"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "Telefon hoparlörü (güvenli)"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Ahize"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Kablolu kulaklık (mikrofonlu)"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Kablolu kulaklık"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB kulaklık"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB ses cihazı"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB aksesuar"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (arama modu)"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (müzik)"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE kulaklık"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE hoparlör"
        AudioDeviceInfo.TYPE_HEARING_AID -> "İşitme cihazı"
        AudioDeviceInfo.TYPE_TELEPHONY -> "Telefon hattı"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Sanal (submix)"
        AudioDeviceInfo.TYPE_FM_TUNER -> "FM alıcı"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog hat"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Dijital hat"
        AudioDeviceInfo.TYPE_BUS -> "Veri yolu"
        TYPE_ECHO_REFERENCE -> "Yankı referansı"
        else -> "Diğer (tip $type)"
    }

    /** Ekranda ve raporda gösterilen kısa tanım: "USB kulaklık · ürün adı · adres (#id)". */
    fun describe(d: AudioDeviceInfo?): String {
        if (d == null) return "—"
        val product = d.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != Build.MODEL }
        val address = if (Build.VERSION.SDK_INT >= 28) d.address?.takeIf { it.isNotBlank() } else null
        return buildString {
            append(typeName(d.type))
            if (product != null) append(" · ").append(product)
            if (address != null) append(" · ").append(address)
            append(" (#").append(d.id).append(')')
        }
    }

    /** Raporda kullanılan ayrıntılı tanım (kanal sayıları, örnekleme hızları). */
    fun describeDetailed(d: AudioDeviceInfo): String {
        val channels = d.channelCounts.distinct().sorted().joinToString(",").ifEmpty { "?" }
        val rates = d.sampleRates.distinct().sorted().joinToString(",").ifEmpty { "her hız" }
        return "${describe(d)} — kanal: $channels, örnekleme: $rates"
    }

    /** Telefonun bildirdiği fiziksel mikrofonlar (Android 9+). */
    fun microphones(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < 28) return emptyList()
        val mics = try {
            audioManager(context).microphones
        } catch (e: Exception) {
            return listOf("okunamadı: ${e.message}")
        }
        return mics.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC || it.type in HEADSET_INPUT_PRIORITY }.map { m ->
            val location = when (m.location) {
                MicrophoneInfo.LOCATION_MAINBODY -> "gövde"
                MicrophoneInfo.LOCATION_MAINBODY_MOVABLE -> "gövde (hareketli)"
                MicrophoneInfo.LOCATION_PERIPHERAL -> "harici"
                else -> "konum ?"
            }
            val dir = when (m.directionality) {
                MicrophoneInfo.DIRECTIONALITY_OMNI -> "her yöne"
                MicrophoneInfo.DIRECTIONALITY_BI_DIRECTIONAL -> "çift yönlü"
                MicrophoneInfo.DIRECTIONALITY_CARDIOID -> "kardioid"
                MicrophoneInfo.DIRECTIONALITY_HYPER_CARDIOID -> "hiperkardioid"
                MicrophoneInfo.DIRECTIONALITY_SUPER_CARDIOID -> "süperkardioid"
                else -> "yön ?"
            }
            val p = m.position
            val pos = if (p == MicrophoneInfo.POSITION_UNKNOWN) "" else
                " @(%.3f, %.3f, %.3f) m".format(p.x, p.y, p.z)
            "${m.description.ifBlank { typeName(m.type) }} · ${typeName(m.type)} · $location · $dir$pos"
        }
    }
}
