package com.mastar.editor.engine.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Simple feedback-delay echo ("Echo" / "Hall" voice effects).
 * out[n] = in[n] + decay * out[n - delay]
 */
@UnstableApi
class EchoAudioProcessor(
    private val delayMs: Int = 180,
    private val decay: Float = 0.45f,
) : BaseAudioProcessor() {

    private var delayBuffer = FloatArray(0)
    private var writeIndex = 0

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
        ensureDelayBuffer()
        val output = replaceOutputBuffer(remaining)
        while (inputBuffer.remaining() >= 2) {
            val dry = inputBuffer.short.toFloat()
            val wet = dry + decay * delayBuffer[writeIndex]
            val clamped = wet.toInt().coerceIn(-32768, 32767)
            delayBuffer[writeIndex] = clamped.toFloat()
            writeIndex = (writeIndex + 1) % delayBuffer.size
            output.putShort(clamped.toShort())
        }
        output.flip()
    }

    private fun ensureDelayBuffer() {
        val samples =
            (inputAudioFormat.sampleRate * inputAudioFormat.channelCount * delayMs / 1000)
                .coerceAtLeast(1)
        if (delayBuffer.size != samples) {
            delayBuffer = FloatArray(samples)
            writeIndex = 0
        }
    }

    override fun onFlush() {
        delayBuffer.fill(0f)
        writeIndex = 0
    }

    override fun onReset() {
        delayBuffer = FloatArray(0)
        writeIndex = 0
    }
}
