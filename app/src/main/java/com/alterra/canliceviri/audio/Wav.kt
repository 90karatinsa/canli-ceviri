package com.alterra.canliceviri.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** 16-bit PCM WAV yazıcı. Başlık kapatılırken doldurulur. */
class WavWriter(val file: File, private val sampleRate: Int, private val channels: Int = 1) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var closed = false

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    @Synchronized
    fun write(pcm: ShortArray, len: Int = pcm.size) {
        if (closed) return
        val bytes = ByteArray(len * 2)
        for (i in 0 until len) {
            val v = pcm[i].toInt()
            bytes[2 * i] = (v and 0xff).toByte()
            bytes[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        raf.write(bytes)
        dataBytes += bytes.size
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        raf.seek(0)
        raf.write(header(dataBytes))
        raf.close()
    }

    private fun header(dataLen: Long): ByteArray {
        val h = ByteArray(44)
        fun str(off: Int, s: String) = s.forEachIndexed { i, c -> h[off + i] = c.code.toByte() }
        fun le32(off: Int, v: Long) { for (i in 0 until 4) h[off + i] = ((v shr (8 * i)) and 0xff).toByte() }
        fun le16(off: Int, v: Int) { h[off] = (v and 0xff).toByte(); h[off + 1] = ((v shr 8) and 0xff).toByte() }
        str(0, "RIFF"); le32(4, 36 + dataLen); str(8, "WAVE")
        str(12, "fmt "); le32(16, 16); le16(20, 1); le16(22, channels)
        le32(24, sampleRate.toLong()); le32(28, sampleRate.toLong() * channels * 2)
        le16(32, channels * 2); le16(34, 16)
        str(36, "data"); le32(40, dataLen)
        return h
    }
}

/** Basit WAV okuyucu: yalnızca 16-bit PCM; çok kanallıysa tek kanala indirir. */
object WavReader {
    class Pcm(val sampleRate: Int, val samples: ShortArray)

    fun read(file: File): Pcm? {
        val b = try { file.readBytes() } catch (_: Exception) { return null }
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        fun le16(o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        fun le32(o: Int) = le16(o) or (le16(o + 2) shl 16)
        var pos = 12
        var format = 1
        var channels = 1
        var rate = 0
        var bits = 16
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = le32(pos + 4)
            val body = pos + 8
            if (id == "fmt " && body + 16 <= b.size) {
                format = le16(body)
                channels = le16(body + 2).coerceAtLeast(1)
                rate = le32(body + 4)
                bits = le16(body + 14)
            } else if (id == "data") {
                if (format != 1 || bits != 16 || rate <= 0) return null
                // Akışla yazan motorlar boyutu 0 ya da -1 bırakabiliyor.
                val len = if (size <= 0 || body + size > b.size) b.size - body else size
                val frames = len / (2 * channels)
                val out = ShortArray(frames) { f ->
                    var sum = 0
                    for (c in 0 until channels) sum += le16(body + (f * channels + c) * 2).toShort().toInt()
                    (sum / channels).toShort()
                }
                return Pcm(rate, out)
            }
            if (size < 0) return null
            pos = body + size + (size and 1)
        }
        return null
    }
}
