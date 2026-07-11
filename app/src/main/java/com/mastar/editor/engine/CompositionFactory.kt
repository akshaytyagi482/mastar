package com.mastar.editor.engine

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import com.mastar.editor.data.db.ClipEntity
import com.mastar.editor.data.db.ClipType
import com.mastar.editor.engine.effects.EffectResolver
import com.mastar.editor.engine.effects.TimedTextOverlay

/**
 * Builds the Media3 Composition that BOTH the preview (CompositionPlayer)
 * and the export (Transformer) play. One code path = true WYSIWYG: per-clip
 * filters, adjustments, transforms, opacity, speed, volume, fades, voice
 * effects, PIP overlays, and burned-in text are identical on screen and in
 * the output file.
 */
@UnstableApi
object CompositionFactory {

    /** Everything the timeline contributes to a render. */
    data class Layers(
        val videoClips: List<ClipEntity>,
        val overlayClips: List<ClipEntity>,
        val audioClips: List<ClipEntity>,
        val textClips: List<ClipEntity>,
    )

    fun build(
        layers: Layers,
        outWidth: Int,
        outHeight: Int,
        includeOverlayTrack: Boolean = true,
    ): Composition {
        require(layers.videoClips.isNotEmpty()) { "Timeline is empty" }

        val mainSequence = mainVideoSequence(layers.videoClips, outWidth, outHeight)

        val overlayClips =
            if (includeOverlayTrack) layers.overlayClips.sortedBy { it.timelineStartMs }
            else emptyList()

        val sequences = buildList {
            add(mainSequence)
            overlaySequence(overlayClips, outWidth, outHeight)?.let { add(it) }
            audioSequence(layers.audioClips)?.let { add(it) }
        }

        val hasLeadingAudioGap =
            layers.audioClips.minOfOrNull { it.timelineStartMs }?.let { it > 0 } ?: false

        // Text proportion is anchored to a 360sp-wide reference canvas.
        val textPxPerSp = minOf(outWidth, outHeight) / 360f

        return Composition.Builder(sequences)
            .setEffects(compositionEffects(layers.textClips, textPxPerSp))
            .setVideoCompositorSettings(PipCompositorSettings(overlayClips))
            .experimentalSetForceAudioTrack(hasLeadingAudioGap)
            .build()
    }

    /**
     * Maps each overlay clip's position/scale/opacity onto the compositor.
     * Presentation time is absolute composition time, so the clip lookup is
     * a direct timeline query — this is what makes PIP placement per-clip.
     */
    private class PipCompositorSettings(
        private val overlayClips: List<ClipEntity>,
    ) : VideoCompositorSettings {

        override fun getOutputSize(inputSizes: List<Size>): Size = inputSizes[0]

        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
            // inputId 0 is the primary sequence; overlays are sequence 1.
            if (inputId != 1) return StaticOverlaySettings.Builder().build()
            val timeMs = presentationTimeUs / 1000
            val clip = overlayClips.firstOrNull {
                timeMs in it.timelineStartMs until it.timelineEndMs
            } ?: return StaticOverlaySettings.Builder().setAlphaScale(0f).build()

            // positionX/Y are 0..1 (top-left origin); NDC anchors are -1..1, y-up.
            val ndcX = clip.positionX * 2f - 1f
            val ndcY = -(clip.positionY * 2f - 1f)
            return StaticOverlaySettings.Builder()
                .setAlphaScale(clip.opacity.coerceIn(0f, 1f))
                .setScale(clip.scale, clip.scale)
                .setRotationDegrees(-clip.rotationDeg)
                .setBackgroundFrameAnchor(ndcX, ndcY)
                .setOverlayFrameAnchor(0f, 0f)
                .build()
        }
    }

    /** Main track: clips back-to-back, black gaps preserved via addGap. */
    private fun mainVideoSequence(
        clips: List<ClipEntity>,
        outWidth: Int,
        outHeight: Int,
    ): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder()
        var cursorMs = 0L
        for (clip in clips.sortedBy { it.timelineStartMs }) {
            val gapMs = clip.timelineStartMs - cursorMs
            if (gapMs > 0) builder.addGap(gapMs * 1000)
            builder.addItem(clip.toEditedMediaItem(outWidth, outHeight, includeTransform = true))
            cursorMs = clip.timelineEndMs
        }
        return builder.build()
    }

    /**
     * PIP track: second video sequence. Gaps between overlay clips render
     * fully transparent via the compositor's alpha (clip lookup misses).
     */
    private fun overlaySequence(
        overlayClips: List<ClipEntity>,
        outWidth: Int,
        outHeight: Int,
    ): EditedMediaItemSequence? {
        if (overlayClips.isEmpty()) return null
        val builder = EditedMediaItemSequence.Builder()
        var cursorMs = 0L
        for (clip in overlayClips) {
            val gapMs = clip.timelineStartMs - cursorMs
            if (gapMs > 0) builder.addGap(gapMs * 1000)
            // Transform is handled by the compositor (scale/rotation/alpha),
            // so item effects carry only color work.
            builder.addItem(
                clip.toEditedMediaItem(
                    outWidth, outHeight,
                    includeTransform = false,
                    muteAudio = true,
                )
            )
            cursorMs = clip.timelineEndMs
        }
        return builder.build()
    }

    /**
     * Music/voiceover sequence: silence gaps position each audio clip at its
     * exact timeline offset; the mixer does the rest.
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
                    .setEffects(Effects(EffectResolver.audioProcessorsFor(clip), emptyList()))
                    .build()
            )
            cursorMs = clip.timelineEndMs
        }
        return builder.build()
    }

    /** Composition-level effects see absolute output time: timed text lives here. */
    private fun compositionEffects(textClips: List<ClipEntity>, textPxPerSp: Float): Effects {
        if (textClips.isEmpty()) return Effects.EMPTY
        val overlays = textClips.mapNotNull { clip ->
            val payload = clip.payload ?: return@mapNotNull null
            TimedTextOverlay(payload, clip.timelineStartMs, clip.timelineEndMs, textPxPerSp)
        }
        if (overlays.isEmpty()) return Effects.EMPTY
        return Effects(
            emptyList(),
            listOf(OverlayEffect(ImmutableList.copyOf<TextureOverlay>(overlays))),
        )
    }

    private fun ClipEntity.toEditedMediaItem(
        outWidth: Int,
        outHeight: Int,
        includeTransform: Boolean,
        muteAudio: Boolean = false,
    ): EditedMediaItem {
        val isImage = type == ClipType.IMAGE

        val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(sourceUri))
        if (isImage) {
            mediaItemBuilder.setImageDurationMs(timelineDurationMs)
        } else {
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(sourceStartMs)
                    .setEndPositionMs(sourceEndMs)
                    .build()
            )
        }

        val videoEffects = buildList {
            if (speed != 1f && !isImage) add(SpeedChangeEffect(speed))
            addAll(EffectResolver.videoEffectsFor(this@toEditedMediaItem, includeTransform))
            add(
                Presentation.createForWidthAndHeight(
                    outWidth, outHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                )
            )
        }

        val builder = EditedMediaItem.Builder(mediaItemBuilder.build())
            .setEffects(
                Effects(
                    if (isImage || muteAudio) emptyList()
                    else EffectResolver.audioProcessorsFor(this),
                    videoEffects,
                )
            )
        if (isImage) builder.setFrameRate(30)
        if (muteAudio && !isImage) builder.setRemoveAudio(true)
        return builder.build()
    }
}
