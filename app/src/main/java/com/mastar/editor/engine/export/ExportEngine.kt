package com.mastar.editor.engine.export

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import com.mastar.editor.data.db.ClipEntity
import com.mastar.editor.engine.effects.FilterLibrary
import com.mastar.editor.engine.effects.TimedTextOverlay
import java.io.File

/**
 * Export pipeline built on Media3 Transformer: reads each clip's timestamp
 * window from the untouched source, applies per-clip GPU effects (speed,
 * color filters), overlays timed text at composition level, and encodes the
 * stitched sequence with the device's hardware MediaCodec.
 */
@UnstableApi
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
     * [textClips] are TEXT clips overlaid across the whole timeline.
     */
    fun export(
        clips: List<ClipEntity>,
        textClips: List<ClipEntity>,
        outputFile: File,
        canvasWidth: Int,
        canvasHeight: Int,
        listener: Listener,
    ) {
        require(clips.isNotEmpty()) { "Timeline is empty" }

        val editedItems = clips.map { it.toEditedMediaItem(canvasWidth, canvasHeight) }
        val composition = Composition.Builder(EditedMediaItemSequence(editedItems))
            .setEffects(compositionEffects(textClips))
            .build()

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
        val holder = ProgressHolder()
        transformer?.getProgress(holder)
        return holder.progress
    }

    fun cancel() {
        transformer?.cancel()
        transformer = null
    }

    /** Composition-level effects see absolute output time: timed text lives here. */
    private fun compositionEffects(textClips: List<ClipEntity>): Effects {
        if (textClips.isEmpty()) return Effects.EMPTY
        val overlays = textClips.mapNotNull { clip ->
            val text = clip.payload ?: return@mapNotNull null
            TimedTextOverlay(text, clip.timelineStartMs, clip.timelineEndMs)
        }
        if (overlays.isEmpty()) return Effects.EMPTY
        return Effects(
            emptyList(),
            listOf(OverlayEffect(ImmutableList.copyOf<TextureOverlay>(overlays))),
        )
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
            // Same GPU filter chain as the preview: WYSIWYG color.
            addAll(FilterLibrary.effectsFor(filterId))
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
