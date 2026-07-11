package com.mastar.editor.engine.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Linear fade-in/fade-out gain ramp over a clip's PCM stream.
 * The processor sees the stream from the clip's start (per-item effects run
 * on the already-clipped item), so sample position maps directly to clip time.
 */
@UnstableApi
class FadeAudioProcessor(
    private val fadeInMs: Long,
    private val fadeOutMs: Long,
    private val clipDurationMs: Long,
) : BaseAudioProcessor() {

    private var framesRead = 0L

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val sampleRate = inputAudioFormat.sampleRate
        val channels = inputAudioFormat.channelCount
        val fadeInFrames = fadeInMs * sampleRate / 1000
        val fadeOutStartFrame = (clipDurationMs - fadeOutMs) * sampleRate / 1000
        val totalFrames = clipDurationMs * sampleRate / 1000

        while (inputBuffer.remaining() >= 2 * channels) {
            var gain = 1f
            if (fadeInFrames > 0 && framesRead < fadeInFrames) {
                gain *= framesRead / fadeInFrames.toFloat()
            }
            if (fadeOutMs > 0 && framesRead >= fadeOutStartFrame) {
                val fadeOutFrames = (totalFrames - fadeOutStartFrame).coerceAtLeast(1)
                gain *= (1f - (framesRead - fadeOutStartFrame) / fadeOutFrames.toFloat())
                    .coerceIn(0f, 1f)
            }
            repeat(channels) {
                val sample = inputBuffer.short
                output.putShort((sample * gain).toInt().coerceIn(-32768, 32767).toShort())
            }
            framesRead++
        }
        output.flip()
    }

    override fun onFlush() {
        framesRead = 0
    }

    override fun onReset() {
        framesRead = 0
    }
}
