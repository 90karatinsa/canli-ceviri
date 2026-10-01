package com.alterra.canliceviri.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Belirli bir çıkış cihazına (kulaklık ya da telefon hoparlörü) mono 16-bit ses çalar.
 * Kulaklık takılıyken bile setPreferredDevice ile ses hoparlöre yönlendirilebiliyor;
 * sistemin gerçekte kullandığı cihaz [routedDevice].
 */
class PcmPlayer(
    val sampleRate: Int,
    requestedDevice: AudioDeviceInfo?,
    usage: Int = AudioAttributes.USAGE_MEDIA,
) {
    private val track: AudioTrack
    private val bufferFrames: Int
    private var framesWritten = 0L

    @Volatile var routedDevice: AudioDeviceInfo? = null; private set

    private val routingListener = AudioRouting.OnRoutingChangedListener { router ->
        router.routedDevice?.let { routedDevice = it }
    }

    init {
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBytes, sampleRate / 10 * 2)) // en az 100 ms
            .build()
        bufferFrames = track.bufferSizeInFrames
        if (requestedDevice != null) track.setPreferredDevice(requestedDevice)
        track.addOnRoutingChangedListener(routingListener, Handler(Looper.getMainLooper()))
        track.play()
    }

    /** Arabellekte yer açılana kadar bekleyerek yazar. */
    fun write(pcm: ShortArray, len: Int = pcm.size) {
        var off = 0
        while (off < len) {
            val n = track.write(pcm, off, len - off)
            if (n <= 0) break
            off += n
        }
        framesWritten += off
        track.routedDevice?.let { routedDevice = it }
    }

    /** Yazılan sesin tamamı çalınana kadar bekler, sonra kapatır. */
    fun finish(timeoutMs: Long = 3000) {
        val target = framesWritten
        // Kısa seslerde AudioTrack arabellek dolmadan çalmaya başlamayabiliyor; sessizlikle doldur.
        write(ShortArray(bufferFrames))
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val head = track.playbackHeadPosition.toLong() and 0xffffffffL
            if (head >= target) break
            Thread.sleep(10)
        }
        release()
    }

    fun release() {
        track.removeOnRoutingChangedListener(routingListener)
        try {
            track.stop()
        } catch (_: IllegalStateException) {
        }
        track.release()
    }
}
