package com.mastar.editor.engine.audio

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Generates exact-length silent WAV files for positioning audio clips on the
 * timeline. CompositionPlayer rejects sequence gaps, so silence is real media.
 * Files are cached per duration; a 10s gap is ~1.7MB of cache, cleared by the
 * OS with the rest of cacheDir.
 */
object Silence {

    private const val SAMPLE_RATE = 44100
    private const val CHANNELS = 2
    private const val BYTES_PER_SAMPLE = 2

    fun wavUri(context: Context, durationMs: Long): Uri {
        val dir = File(context.cacheDir, "silence").apply { mkdirs() }
        val file = File(dir, "silence_$durationMs.wav")
        if (!file.exists() || file.length() == 0L) {
            writeWav(file, durationMs)
        }
        return Uri.fromFile(file)
    }

    private fun writeWav(file: File, durationMs: Long) {
        val frameCount = (SAMPLE_RATE * durationMs / 1000).toInt()
        val dataSize = frameCount * CHANNELS * BYTES_PER_SAMPLE
        val byteRate = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE

        file.outputStream().buffered().use { out ->
            fun writeInt(v: Int) = out.write(
                byteArrayOf(
                    (v and 0xFF).toByte(),
                    (v shr 8 and 0xFF).toByte(),
                    (v shr 16 and 0xFF).toByte(),
                    (v shr 24 and 0xFF).toByte(),
                )
            )

            fun writeShort(v: Int) =
                out.write(byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte()))

            out.write("RIFF".toByteArray())
            writeInt(36 + dataSize)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            writeInt(16)
            writeShort(1) // PCM
            writeShort(CHANNELS)
            writeInt(SAMPLE_RATE)
            writeInt(byteRate)
            writeShort(CHANNELS * BYTES_PER_SAMPLE) // block align
            writeShort(16) // bits per sample
            out.write("data".toByteArray())
            writeInt(dataSize)

            val zeros = ByteArray(8192)
            var remaining = dataSize
            while (remaining > 0) {
                val n = minOf(remaining, zeros.size)
                out.write(zeros, 0, n)
                remaining -= n
            }
        }
    }
}
