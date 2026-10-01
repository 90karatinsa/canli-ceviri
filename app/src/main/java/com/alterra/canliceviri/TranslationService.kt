package com.alterra.canliceviri

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.alterra.canliceviri.pipeline.ConversationEngine
import com.alterra.canliceviri.pipeline.Mode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Mikrofon türünde ön plan hizmeti: motor bunun içinde çalışır ki ekran kapansa ya da uygulama arkaya düşse de
 * mikrofon susturulmasın. Android 14+ bu hizmeti yalnızca uygulama ekrandayken başlatmaya izin veriyor; bu yüzden
 * Başlat'tan başlatılır, arama kesintilerinde kapatılmaz (motor içeride duraklar).
 *
 * `startForegroundService` sonrası 5 sn içinde `startForeground` çağrılmalı; model yükleme ondan sonra başlar.
 */
class TranslationService : Service() {

    private val engine: ConversationEngine get() = CanliCeviriApp.of(this).engine
    private var startJob: Job? = null
    private var watchJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Çeviri", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Çeviri dinlerken gösterilir"
                setShowBadge(false)
            }
        )
        // Mikrofon açıkken sistem işlemciyi zaten uyanık tutuyor; bu kilit kesinti aralarında (mikrofon kapalı,
        // çeviri sürüyor) ekran kapalıyken işlem bitsin diye.
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CanliCeviri:motor")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            engine.stop()
            return START_NOT_STICKY
        }
        goForeground(notification("Çeviri hazırlanıyor…"))
        wakeLock?.acquire(WAKE_LOCK_MAX_MS)

        val mode = intent?.getStringExtra(EXTRA_MODE)?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.CONVERSATION
        // Yeniden başlatmada (mod değişti) eski izleyici motorun geçici "durdu" anını bitiş sanmasın.
        watchJob?.cancel()
        startJob?.cancel()
        val scope = CanliCeviriApp.of(this).appScope
        startJob = scope.launch {
            engine.start(ConversationEngine.Settings(mode = mode))
            if (!engine.status.value.running) {
                Log.w(TAG, "motor başlayamadı: ${engine.status.value.error}")
                stopSelf()
                return@launch
            }
            watchJob = scope.launch {
                var lastText: String? = null
                engine.status.collect { s ->
                    if (!s.running) {
                        stopSelf()
                        return@collect
                    }
                    val text = if (s.paused != null) "Duraklatıldı: ${s.paused}" else "Çeviri dinliyor"
                    if (text != lastText) {
                        lastText = text
                        notify(notification(text))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        startJob?.cancel()
        watchJob?.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun notify(n: Notification) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, n)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TranslationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Canlı Çeviri")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Durdur", stop)
            .build()
    }

    companion object {
        private const val TAG = "CanliCeviri"
        private const val CHANNEL = "ceviri"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.alterra.canliceviri.DURDUR"
        private const val EXTRA_MODE = "mod"
        private const val WAKE_LOCK_MAX_MS = 6 * 60 * 60 * 1000L

        /** Ekrandayken çağrılmalı (Android 14+ kuralı); izin ya da arka plan hatası fırlatabilir. */
        fun start(context: Context, mode: Mode) {
            val intent = Intent(context, TranslationService::class.java).putExtra(EXTRA_MODE, mode.name)
            context.startForegroundService(intent)
        }
    }
}
