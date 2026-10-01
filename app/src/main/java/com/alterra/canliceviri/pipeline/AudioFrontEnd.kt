package com.alterra.canliceviri.pipeline

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.alterra.canliceviri.audio.AudioDevices
import com.alterra.canliceviri.audio.MicCapture
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Mikrofon girişi. Kulaklık mikrofonu varsa iki kayıt açar:
 * - kullanıcı: kulaklık mikrofonu, "görüşme" türü (Android'in gürültü/yankı engellemesi açık)
 * - karşı taraf: telefon mikrofonu, "konuşma tanıma" türü
 * Pixel'deki taramada iki mikrofon ancak türlerden biri "görüşme" olunca ayrı açılabildi.
 * Sistem iki kaydı aynı mikrofona bağlarsa tek mikrofona düşer.
 *
 * Çerçeveler (512 örnek, 32 ms) tek bir iş parçacığında çift halinde [onFrames]'e verilir.
 */
class AudioFrontEnd(private val context: Context) {

    class Frames(val user: ShortArray?, val partner: ShortArray, val levelUser: Float, val levelPartner: Float)

    var mode: MicMode = MicMode.NONE; private set
    var info: String = ""; private set

    private var capUser: MicCapture? = null
    private var capPartner: MicCapture? = null
    private val qUser = ArrayBlockingQueue<ShortArray>(QUEUE)
    private val qPartner = ArrayBlockingQueue<ShortArray>(QUEUE)
    @Volatile private var running = false
    private var pump: Thread? = null

    /** Engellenir (~0,5 sn); ana iş parçacığında çağırmayın. */
    fun start(preferDual: Boolean, onFrames: (Frames) -> Unit): MicMode {
        stop()
        qUser.clear()
        qPartner.clear()
        val builtin = AudioDevices.builtinMic(context)
        val headset = if (preferDual) AudioDevices.headsetMic(context) else null

        if (headset != null && builtin != null) {
            val u = MicCapture("kullanici", headset, MediaRecorder.AudioSource.VOICE_COMMUNICATION, onFrame = { offer(qUser, it) })
            val p = MicCapture("karsi", builtin, MediaRecorder.AudioSource.VOICE_RECOGNITION, onFrame = { offer(qPartner, it) })
            if (u.start() && p.start()) {
                Thread.sleep(400)
                val ru = u.routedDevice
                val rp = p.routedDevice
                val separate = ru != null && rp != null && ru.id != rp.id && !silenced(u) && !silenced(p)
                if (separate) {
                    capUser = u
                    capPartner = p
                    mode = MicMode.DUAL
                    info = "Çift mikrofon: ${AudioDevices.typeName(ru!!.type)} + ${AudioDevices.typeName(rp!!.type)}"
                } else {
                    Log.w(TAG, "İki mikrofon ayrı açılamadı (A=${AudioDevices.describe(ru)}, B=${AudioDevices.describe(rp)}); tek mikrofona geçiliyor")
                    u.stop()
                    p.stop()
                }
            } else {
                u.stop()
                p.stop()
            }
        }

        if (mode != MicMode.DUAL) {
            qUser.clear()
            qPartner.clear()
            val p = MicCapture("tek", builtin, MediaRecorder.AudioSource.VOICE_RECOGNITION, onFrame = { offer(qPartner, it) })
            if (!p.start()) {
                mode = MicMode.NONE
                info = "Mikrofon açılamadı: ${p.error}"
                return mode
            }
            capPartner = p
            mode = MicMode.SINGLE
            info = if (headset != null) "Tek mikrofon (telefon); kulaklık mikrofonu aynı anda açılamadı" else "Tek mikrofon (telefon)"
        }

        running = true
        val dual = mode == MicMode.DUAL
        pump = Thread({
            while (running) {
                val partner = qPartner.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val user = if (dual) qUser.poll(200, TimeUnit.MILLISECONDS) else null
                if (dual && user == null) continue
                onFrames(Frames(user, partner, capUser?.levelDb ?: -120f, capPartner?.levelDb ?: -120f))
            }
        }, "ses-girisi").apply { start() }
        return mode
    }

    fun stop() {
        running = false
        pump?.join(500)
        pump = null
        capUser?.stop()
        capPartner?.stop()
        capUser = null
        capPartner = null
        mode = MicMode.NONE
    }

    private fun silenced(c: MicCapture): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val am = AudioDevices.audioManager(context)
        return am.activeRecordingConfigurations.firstOrNull { it.clientAudioSessionId == c.sessionId }?.isClientSilenced == true
    }

    /**
     * Kayıtlardan biri sistemce susturulmuş mu (arama, başka uygulama)? Resmi bayrak yoksa (eski HAL) 1 sn boyunca
     * tamamen sıfır çerçeve gelmesi de susturulmuş sayılır.
     */
    fun anySilenced(): Boolean {
        val caps = listOfNotNull(capUser, capPartner)
        if (caps.isEmpty()) return false
        if (caps.any { it.zeroRun >= ZERO_RUN_SILENCED }) return true
        if (Build.VERSION.SDK_INT < 29) return false
        val configs = AudioDevices.audioManager(context).activeRecordingConfigurations
        return caps.any { c -> configs.firstOrNull { it.clientAudioSessionId == c.sessionId }?.isClientSilenced == true }
    }

    /** Çift mikrofonda iki kayıt aynı cihaza düşmüş (kulaklık çıktı, arama sonrası yönlendirme değişti). */
    fun routedSame(): Boolean {
        val a = capUser?.routedDevice ?: return false
        val b = capPartner?.routedDevice ?: return false
        return a.id == b.id
    }

    /** Kuyruk dolarsa (işleyici geride kaldıysa) en eski çerçeveyi at. */
    private fun offer(q: ArrayBlockingQueue<ShortArray>, frame: ShortArray) {
        while (!q.offer(frame)) q.poll()
    }

    val routedUser: AudioDeviceInfo? get() = capUser?.routedDevice

    /** ~100 ms yumuşatılmış seviyeler (dBFS). */
    val levelUser: Float get() = capUser?.smoothDb ?: -120f
    val levelPartner: Float get() = capPartner?.smoothDb ?: -120f
    val routedPartner: AudioDeviceInfo? get() = capPartner?.routedDevice

    companion object {
        private const val TAG = "CanliCeviri"
        private const val QUEUE = 64 // ~2 sn
        private const val ZERO_RUN_SILENCED = 31 // 1 sn
    }
}
