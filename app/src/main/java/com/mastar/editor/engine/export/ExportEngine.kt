package com.mastar.editor.engine.export

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import com.mastar.editor.data.db.ClipEntity
import com.mastar.editor.data.db.ClipType
import com.mastar.editor.engine.effects.EffectResolver
import com.mastar.editor.engine.effects.TimedTextOverlay
import java.io.File

/**
 * Export pipeline built on Media3 Transformer: reads each clip's timestamp
 * window from the untouched source, applies the exact same per-clip GPU/audio
 * chains as the preview (via EffectResolver), overlays timed text, mixes the
 * music track, and encodes with the device's hardware MediaCodec.
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
        clips: List<ClipEntity>,
        textClips: List<ClipEntity>,
        audioClips: List<ClipEntity>,
        outputFile: File,
        canvasWidth: Int,
        canvasHeight: Int,
        settings: Settings,
        listener: Listener,
    ) {
        require(clips.isNotEmpty()) { "Timeline is empty" }

        // Scale the canvas to the requested resolution, keeping aspect.
        val shortSide = minOf(canvasWidth, canvasHeight)
        val scale = settings.resolution / shortSide.toFloat()
        val outWidth = (canvasWidth * scale).toInt().evenAligned()
        val outHeight = (canvasHeight * scale).toInt().evenAligned()

        val videoSequence = EditedMediaItemSequence.Builder(
            clips.map { it.toEditedMediaItem(outWidth, outHeight) }
        ).build()

        val sequences = buildList {
            add(videoSequence)
            audioSequence(audioClips)?.let { add(it) }
        }

        val composition = Composition.Builder(sequences)
            .setEffects(compositionEffects(textClips))
            .build()

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

    /**
     * Music/voiceover as a parallel sequence: silence gaps position each
     * audio clip at its exact timeline offset, Transformer mixes the rest.
     */
    private fun audioSequence(audioClips: List<ClipEntity>): EditedMediaItemSequence? {
        if (audioClips.isEmpty()) return null
        val builder = EditedMediaItemSequence.Builder()
        var cursorMs = 0L
        for (clip in audioClips.sortedBy { it.timelineStartMs }) {
            val gapMs = clip.timelineStartMs - cursorMs
            if (gapMs > 0) builder.addGap(gapMs * 1000)
            val item = MediaItem.Builder()
                .setUri(Uri.parse(clip.sourceUri))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.sourceStartMs)
                        .setEndPositionMs(clip.sourceEndMs)
                        .build()
                )
                .build()
            builder.addItem(
                EditedMediaItem.Builder(item)
                    .setRemoveVideo(true)
                    .setEffects(
                        Effects(EffectResolver.audioProcessorsFor(clip), emptyList())
                    )
                    .build()
            )
            cursorMs = clip.timelineEndMs
        }
        return builder.build()
    }

    /** Composition-level effects see absolute output time: timed text lives here. */
    private fun compositionEffects(textClips: List<ClipEntity>): Effects {
        if (textClips.isEmpty()) return Effects.EMPTY
        val overlays = textClips.mapNotNull { clip ->
            val payload = clip.payload ?: return@mapNotNull null
            TimedTextOverlay(payload, clip.timelineStartMs, clip.timelineEndMs)
        }
        if (overlays.isEmpty()) return Effects.EMPTY
        return Effects(
            emptyList(),
            listOf(OverlayEffect(ImmutableList.copyOf<TextureOverlay>(overlays))),
        )
    }

    private fun ClipEntity.toEditedMediaItem(outWidth: Int, outHeight: Int): EditedMediaItem {
        val isImage = type == ClipType.IMAGE

        val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(sourceUri))
        if (!isImage) {
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(sourceStartMs)
                    .setEndPositionMs(sourceEndMs)
                    .build()
            )
        } else {
            mediaItemBuilder.setImageDurationMs(timelineDurationMs)
        }

        val videoEffects = buildList {
            if (speed != 1f && !isImage) add(SpeedChangeEffect(speed))
            addAll(EffectResolver.videoEffectsFor(this@toEditedMediaItem))
            // Letterbox/pillarbox every clip into the output canvas.
            add(
                Presentation.createForWidthAndHeight(
                    outWidth, outHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                )
            )
        }

        val builder = EditedMediaItem.Builder(mediaItemBuilder.build())
            .setEffects(
                Effects(
                    if (isImage) emptyList() else EffectResolver.audioProcessorsFor(this),
                    videoEffects,
                )
            )
        if (isImage) builder.setFrameRate(30)
        return builder.build()
    }

    private fun Int.evenAligned() = this - (this % 2)
}
