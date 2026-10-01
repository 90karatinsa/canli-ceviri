package com.alterra.canliceviri.report

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.media.AudioManager
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Raporların başına yazılan cihaz bilgisi (cihaz testi ve sorun bildirimi ortak). */
object DeviceInfo {

    fun summary(context: Context): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        val mem = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem)
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "$soc · RAM ${"%.1f".format(mem.totalMem / 1e9)} GB · işlenmemiş kayıt ${if (unprocessed) "var" else "yok"}"
    }

    /** Uygulama en son nasıl kapandı (sistem öldürdü mü, çöktü mü)? API 30+; yoksa null. */
    fun lastExit(context: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = try { am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() } catch (_: Exception) { null } ?: return null
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_USER_REQUESTED -> "kullanıcı kapattı"
            ApplicationExitInfo.REASON_USER_STOPPED -> "kullanıcı durdurdu"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "bellek yetmedi (sistem kapattı)"
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "çöktü"
            ApplicationExitInfo.REASON_ANR -> "yanıt vermedi (ANR)"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "aşırı kaynak kullanımı (sistem kapattı)"
            ApplicationExitInfo.REASON_OTHER -> "sistem kapattı"
            ApplicationExitInfo.REASON_SIGNALED -> "sinyalle kapandı"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "izin değişti"
            else -> "neden ${info.reason}"
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(info.timestamp))
        return "$time · $reason${info.description?.let { " · $it" } ?: ""}"
    }
}
