package com.mastar.editor.engine.export

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.mastar.editor.engine.CompositionFactory
import java.io.File

/**
 * Export pipeline: encodes the SAME Composition the preview plays
 * (CompositionFactory), through the device's hardware MediaCodec.
 */
@UnstableApi
class ExportEngine(private val context: Context) {

    /** User-facing export options (CapCut-style export sheet). */
    data class Settings(
        /** Short-side pixels: 480 / 720 / 1080 / 1440. */
        val resolution: Int = 1080,
        /** H.264 bitrate in bps. */
        val bitrate: Int = 10_000_000,
    )

    sealed interface State {
        data object Idle : State
        data class Exporting(val progressPercent: Int) : State
        data class Done(val outputFile: File) : State
        data class Failed(val cause: Exception) : State
    }

    fun interface Listener {
        fun onState(state: State)
    }

    private var transformer: Transformer? = null

    fun export(
        layers: CompositionFactory.Layers,
        outputFile: File,
        canvasWidth: Int,
        canvasHeight: Int,
        settings: Settings,
        listener: Listener,
    ) {
        // Scale the canvas to the requested resolution, keeping aspect.
        val shortSide = minOf(canvasWidth, canvasHeight)
        val scale = settings.resolution / shortSide.toFloat()
        val outWidth = ((canvasWidth * scale).toInt()) and -2
        val outHeight = ((canvasHeight * scale).toInt()) and -2

        val composition: Composition = CompositionFactory.build(layers, outWidth, outHeight)

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(settings.bitrate)
                    .build()
            )
            .build()

        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    listener.onState(State.Done(outputFile))
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    listener.onState(State.Failed(exportException))
                }
            })
            .build()

        transformer = t
        listener.onState(State.Exporting(0))
        t.start(composition, outputFile.absolutePath)
    }

    /** Polls export progress; call from a coroutine ticker while exporting. */
    fun queryProgress(): Int {
        val holder = ProgressHolder()
        transformer?.getProgress(holder)
        return holder.progress
    }

    fun cancel() {
        transformer?.cancel()
        transformer = null
    }
}
