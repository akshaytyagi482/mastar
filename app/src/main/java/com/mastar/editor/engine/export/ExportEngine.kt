package com.mastar.editor.engine.export

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.mastar.editor.data.db.ClipEntity
import java.io.File

/**
 * Export pipeline built on Media3 Transformer: reads each clip's timestamp
 * window from the untouched source, applies per-clip effects, and encodes
 * the stitched sequence with the device's hardware MediaCodec.
 */
class ExportEngine(private val context: Context) {

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

    /**
     * Exports the main video track to [outputFile] as H.264/AAC MP4 —
     * the most widely hardware-supported combination on Indian devices.
     */
    fun export(
        clips: List<ClipEntity>,
        outputFile: File,
        canvasWidth: Int,
        canvasHeight: Int,
        listener: Listener,
    ) {
        require(clips.isNotEmpty()) { "Timeline is empty" }

        val editedItems = clips.map { it.toEditedMediaItem(canvasWidth, canvasHeight) }
        val composition = Composition.Builder(EditedMediaItemSequence(editedItems)).build()

        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
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
        val holder = androidx.media3.transformer.ProgressHolder()
        transformer?.getProgress(holder)
        return holder.progress
    }

    fun cancel() {
        transformer?.cancel()
        transformer = null
    }

    private fun ClipEntity.toEditedMediaItem(canvasWidth: Int, canvasHeight: Int): EditedMediaItem {
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(sourceUri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(sourceStartMs)
                    .setEndPositionMs(sourceEndMs)
                    .build()
            )
            .build()

        val videoEffects = buildList {
            if (speed != 1f) add(SpeedChangeEffect(speed))
            // Letterbox/pillarbox every clip into the project canvas.
            add(
                Presentation.createForWidthAndHeight(
                    canvasWidth, canvasHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                )
            )
        }

        val audioProcessors = buildList {
            if (speed != 1f) {
                // Sonic keeps pitch natural while audio follows the video speed.
                add(SonicAudioProcessor().apply { setSpeed(speed) })
            }
        }

        return EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(audioProcessors, videoEffects))
            .build()
    }
}
