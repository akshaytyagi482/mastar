package com.mastar.editor.engine

import android.content.Context
import android.net.Uri
import androidx.media3.common.Effect
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
import com.mastar.editor.data.db.KeyframeEntity
import com.mastar.editor.engine.audio.Silence
import com.mastar.editor.engine.effects.EffectResolver
import com.mastar.editor.engine.effects.ImagePipOverlay
import com.mastar.editor.engine.effects.TimedFilterLayer
import com.mastar.editor.engine.effects.TimedTextOverlay
import com.mastar.editor.engine.effects.Transitions
import com.mastar.editor.engine.keyframe.KeyframeEngine
import com.mastar.editor.engine.speed.SpeedCurve

/**
 * Builds the Media3 Composition that BOTH the preview (CompositionPlayer)
 * and the export (Transformer) play. One code path = true WYSIWYG.
 *
 * Per-item effect chain order (the v0.6 "transform does nothing" fix):
 *   speed → color (adjust/filter/opacity) → Presentation (canvas fit)
 *   → transform matrix (position/scale/rotation, keyframable)
 *   → transition ramps
 *
 * CompositionPlayer constraints honored (each was once a black-screen bug):
 * no sequence gaps (real filler media) and durationUs on every item.
 */
@UnstableApi
object CompositionFactory {

    /** Everything the timeline contributes to a render. */
    data class Layers(
        val videoClips: List<ClipEntity>,
        val overlayClips: List<ClipEntity>,
        val audioClips: List<ClipEntity>,
        val textClips: List<ClipEntity>,
        val filterClips: List<ClipEntity> = emptyList(),
        /** Keyframe diamonds per clip id (animates transform/opacity). */
        val keyframesByClip: Map<Long, List<KeyframeEntity>> = emptyMap(),
    )

    private const val GAP_IMAGE_URI = "asset:///gap_black.png"
    private const val MIN_TRANSITION_WINDOW_US = 200_000L

    fun build(
        context: Context,
        layers: Layers,
        outWidth: Int,
        outHeight: Int,
        includeOverlayTrack: Boolean = true,
    ): Composition {
        require(layers.videoClips.isNotEmpty()) { "Timeline is empty" }

        val mainSequence = videoSequence(
            layers.videoClips.sortedBy { it.timelineStartMs },
            layers.keyframesByClip,
            outWidth, outHeight,
            isPipLane = false,
            withTransitions = true,
        )

        // Photo PIP renders via composition-level overlays (single-input
        // safe); only VIDEO PIP needs the multi-input compositor.
        val allOverlayClips =
            if (includeOverlayTrack) layers.overlayClips.sortedBy { it.timelineStartMs }
            else emptyList()
        val videoPipClips = allOverlayClips.filter { it.type == ClipType.VIDEO }
        val imagePipClips = allOverlayClips.filter { it.type == ClipType.IMAGE }

        val sequences = buildList {
            add(mainSequence)
            if (videoPipClips.isNotEmpty()) {
                add(
                    videoSequence(
                        videoPipClips, layers.keyframesByClip, outWidth, outHeight,
                        isPipLane = true,
                        withTransitions = false,
                    )
                )
            }
            audioSequence(context, layers.audioClips)?.let { add(it) }
        }

        // Text proportion is anchored to a 360sp-wide reference canvas.
        val textPxPerSp = minOf(outWidth, outHeight) / 360f

        return Composition.Builder(sequences)
            .setEffects(
                compositionEffects(
                    context, layers, imagePipClips, textPxPerSp,
                    minOf(outWidth, outHeight),
                )
            )
            .setVideoCompositorSettings(
                PipCompositorSettings(videoPipClips, layers.keyframesByClip)
            )
            .build()
    }

    /** True when the composition needs the multi-input video graph. */
    fun needsMultipleInputs(layers: Layers): Boolean =
        layers.overlayClips.any { it.type == ClipType.VIDEO }

    /**
     * Maps each VIDEO overlay clip's (possibly keyframed) placement onto the
     * compositor. Presentation time is absolute composition time. Gap-filler
     * frames between overlay clips miss the lookup → fully transparent.
     */
    private class PipCompositorSettings(
        private val overlayClips: List<ClipEntity>,
        private val keyframesByClip: Map<Long, List<KeyframeEntity>>,
    ) : VideoCompositorSettings {

        override fun getOutputSize(inputSizes: List<Size>): Size = inputSizes[0]

        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
            // inputId 0 is the primary sequence; overlays are sequence 1.
            if (inputId != 1) return StaticOverlaySettings.Builder().build()
            val timeMs = presentationTimeUs / 1000
            val clip = overlayClips.firstOrNull {
                timeMs in it.timelineStartMs until it.timelineEndMs
            } ?: return StaticOverlaySettings.Builder().setAlphaScale(0f).build()

            val kfs = keyframesByClip[clip.id].orEmpty().groupBy { it.property }
            val t = KeyframeEngine.transformAt(
                kfs, timeMs - clip.timelineStartMs,
                clip.positionX, clip.positionY, clip.scale,
                clip.rotationDeg, clip.opacity, clip.volume,
            )

            val ndcX = t.positionX * 2f - 1f
            val ndcY = -(t.positionY * 2f - 1f)
            return StaticOverlaySettings.Builder()
                .setAlphaScale(t.opacity.coerceIn(0f, 1f))
                .setScale(t.scale, t.scale)
                .setRotationDegrees(-t.rotationDeg)
                .setBackgroundFrameAnchor(ndcX, ndcY)
                .setOverlayFrameAnchor(0f, 0f)
                .build()
        }
    }

    /** A video-lane sequence: clips at exact offsets, black frames in gaps. */
    private fun videoSequence(
        clips: List<ClipEntity>,
        keyframesByClip: Map<Long, List<KeyframeEntity>>,
        outWidth: Int,
        outHeight: Int,
        isPipLane: Boolean,
        withTransitions: Boolean,
    ): EditedMediaItemSequence {
        val builder = EditedMediaItemSequence.Builder()
        var cursorMs = 0L
        for ((index, clip) in clips.withIndex()) {
            val gapMs = clip.timelineStartMs - cursorMs
            if (gapMs > 0) builder.addItem(blackFiller(gapMs, outWidth, outHeight))

            val prev = if (withTransitions) clips.getOrNull(index - 1) else null
            val headTransition = prev?.transitionId?.takeIf { prev.transitionDurationMs > 0 }
            val headWindowUs = if (headTransition != null) {
                (prev.transitionDurationMs * 1000).coerceAtLeast(MIN_TRANSITION_WINDOW_US)
            } else 0L
            val tailTransition =
                if (withTransitions) clip.transitionId?.takeIf { clip.transitionDurationMs > 0 }
                else null
            val tailWindowUs = if (tailTransition != null) {
                (clip.transitionDurationMs * 1000).coerceAtLeast(MIN_TRANSITION_WINDOW_US)
            } else 0L

            builder.addItem(
                clip.toEditedMediaItem(
                    outWidth, outHeight,
                    isPipLane = isPipLane,
                    keyframes = keyframesByClip[clip.id].orEmpty(),
                    headTransition = headTransition,
                    headWindowUs = headWindowUs,
                    tailTransition = tailTransition,
                    tailWindowUs = tailWindowUs,
                )
            )
            cursorMs = clip.timelineEndMs
        }
        return builder.build()
    }

    /** Black still stretched over a timeline hole (invisible on PIP lanes). */
    private fun blackFiller(durationMs: Long, outWidth: Int, outHeight: Int): EditedMediaItem =
        EditedMediaItem.Builder(
            MediaItem.Builder()
                .setUri(Uri.parse(GAP_IMAGE_URI))
                .setImageDurationMs(durationMs)
                .build()
        )
            .setFrameRate(30)
            .setEffects(
                Effects(
                    emptyList(),
                    listOf(
                        Presentation.createForWidthAndHeight(
                            outWidth, outHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                        )
                    ),
                )
            )
            .build()

    /**
     * Music/voiceover sequence: generated silence positions each audio clip
     * at its exact timeline offset; the mixer does the rest.
     */
    private fun audioSequence(
        context: Context,
        audioClips: List<ClipEntity>,
    ): EditedMediaItemSequence? {
        val audible = audioClips.filter { !it.muted }
        if (audible.isEmpty()) return null
        val builder = EditedMediaItemSequence.Builder()
        var cursorMs = 0L
        for (clip in audible.sortedBy { it.timelineStartMs }) {
            val gapMs = clip.timelineStartMs - cursorMs
            if (gapMs > 0) {
                builder.addItem(
                    EditedMediaItem.Builder(
                        MediaItem.fromUri(Silence.wavUri(context, gapMs))
                    )
                        .setDurationUs(gapMs * 1000)
                        .build()
                )
            }
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
                    .setDurationUs(clip.fullSourceDurationUs())
                    .setEffects(Effects(EffectResolver.audioProcessorsFor(clip), emptyList()))
                    .build()
            )
            cursorMs = clip.timelineEndMs
        }
        return builder.build()
    }

    /**
     * Composition-level effects (absolute output time): timed text, photo
     * PIP overlays, then filter layers grading the fully composited frame.
     */
    private fun compositionEffects(
        context: Context,
        layers: Layers,
        imagePipClips: List<ClipEntity>,
        textPxPerSp: Float,
        outShortSide: Int,
    ): Effects {
        val overlays = buildList<TextureOverlay> {
            imagePipClips.forEach { clip ->
                add(
                    ImagePipOverlay(
                        context, clip, layers.keyframesByClip[clip.id].orEmpty(), outShortSide
                    )
                )
            }
            layers.textClips.forEach { clip ->
                clip.payload?.let { payload ->
                    add(
                        TimedTextOverlay(
                            payload, clip.timelineStartMs, clip.timelineEndMs, textPxPerSp
                        )
                    )
                }
            }
        }

        val videoEffects = buildList<Effect> {
            if (overlays.isNotEmpty()) add(OverlayEffect(ImmutableList.copyOf(overlays)))
            layers.filterClips.forEach { clip ->
                TimedFilterLayer.effectFor(clip)?.let { add(it) }
            }
        }
        if (videoEffects.isEmpty()) return Effects.EMPTY
        return Effects(emptyList(), videoEffects)
    }

    /** durationUs is the duration BEFORE clipping (the full source file). */
    private fun ClipEntity.fullSourceDurationUs(): Long =
        (if (sourceDurationMs > 0) sourceDurationMs else sourceEndMs) * 1000

    private fun ClipEntity.toEditedMediaItem(
        outWidth: Int,
        outHeight: Int,
        isPipLane: Boolean,
        keyframes: List<KeyframeEntity>,
        headTransition: String?,
        headWindowUs: Long,
        tailTransition: String?,
        tailWindowUs: Long,
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

        val curve = SpeedCurve.parse(speedCurveJson)
        val itemDurationUs = timelineDurationMs * 1000

        val videoEffects = buildList {
            // Speed first: downstream effect timestamps are output-time.
            if (!isImage) {
                if (curve != null) {
                    add(SpeedChangeEffect(curve.toSpeedProvider(sourceEndMs - sourceStartMs)))
                } else if (speed != 1f) {
                    add(SpeedChangeEffect(speed))
                }
            }
            addAll(EffectResolver.colorEffectsFor(this@toEditedMediaItem, keyframes))
            add(
                Presentation.createForWidthAndHeight(
                    outWidth, outHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                )
            )
            // Transform AFTER the canvas fit so it's actually visible.
            // PIP lanes get their placement from the compositor instead.
            if (!isPipLane) {
                EffectResolver.transformEffectFor(this@toEditedMediaItem, keyframes)
                    ?.let { add(it) }
            }
            if (headTransition != null && headWindowUs > 0) {
                addAll(Transitions.inEffects(headTransition, headWindowUs))
            }
            if (tailTransition != null && tailWindowUs > 0 && itemDurationUs > 0) {
                addAll(Transitions.outEffects(tailTransition, itemDurationUs, tailWindowUs))
            }
        }

        val dropAudio = isImage || isPipLane || muted
        val builder = EditedMediaItem.Builder(mediaItemBuilder.build())
            .setEffects(
                Effects(
                    if (dropAudio) emptyList() else EffectResolver.audioProcessorsFor(this),
                    videoEffects,
                )
            )
        if (isImage) {
            builder.setFrameRate(30)
        } else {
            builder.setDurationUs(fullSourceDurationUs())
        }
        if (dropAudio && !isImage) builder.setRemoveAudio(true)
        return builder.build()
    }
}
