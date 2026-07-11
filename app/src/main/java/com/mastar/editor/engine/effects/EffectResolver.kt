package com.mastar.editor.engine.effects

import android.graphics.Matrix
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.SpeedChangingAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.AlphaScale
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import com.mastar.editor.data.db.ClipEntity
import com.mastar.editor.data.db.KeyframeEntity
import com.mastar.editor.data.db.KeyframeProperty
import com.mastar.editor.engine.audio.EchoAudioProcessor
import com.mastar.editor.engine.audio.FadeAudioProcessor
import com.mastar.editor.engine.keyframe.KeyframeEngine
import com.mastar.editor.engine.speed.SpeedCurve

/**
 * The single source of truth that turns a clip's persisted edit state into
 * Media3 effect chains. Preview (CompositionPlayer) and export (Transformer)
 * both consume this via CompositionFactory — guaranteeing WYSIWYG.
 */
@UnstableApi
object EffectResolver {

    data class VoiceEffect(val id: String, val displayName: String, val pitch: Float, val echo: Boolean)

    val VOICE_EFFECTS = listOf(
        VoiceEffect("deep", "Deep", pitch = 0.65f, echo = false),
        VoiceEffect("chipmunk", "Chipmunk", pitch = 1.6f, echo = false),
        VoiceEffect("robot", "Robot", pitch = 0.85f, echo = true),
        VoiceEffect("echo", "Echo", pitch = 1f, echo = true),
    )

    /** Everything time-varying a clip item needs beyond its static fields. */
    data class RenderContext(
        val keyframes: List<KeyframeEntity> = emptyList(),
        /** Presented duration of this item on the timeline, microseconds. */
        val itemDurationUs: Long = 0,
        /** Transition OUT of this clip (applied on its tail). */
        val tailTransitionId: String? = null,
        val tailWindowUs: Long = 0,
        /** Transition INTO this clip (the previous clip's transition). */
        val headTransitionId: String? = null,
        val headWindowUs: Long = 0,
    )

    /**
     * Per-clip video chain: (keyframed) transform → manual color adjustments
     * → filter at intensity → opacity → transition ramps.
     *
     * [includeTransform] is false for PIP overlay clips, whose placement is
     * applied by the video compositor instead (flip stays local).
     */
    fun videoEffectsFor(
        clip: ClipEntity,
        includeTransform: Boolean = true,
        context: RenderContext = RenderContext(),
    ): List<Effect> = buildList {
        val transformKeyframes = context.keyframes.filter {
            it.property != KeyframeProperty.VOLUME
        }

        if (includeTransform && transformKeyframes.isNotEmpty()) {
            addAll(keyframedTransformEffects(clip, transformKeyframes))
        } else {
            // Static transform. Flip is a negative scale.
            val scaleX = (if (includeTransform) clip.scale else 1f) * (if (clip.flipH) -1f else 1f)
            val scaleY = (if (includeTransform) clip.scale else 1f) * (if (clip.flipV) -1f else 1f)
            val rotation = if (includeTransform) clip.rotationDeg else 0f
            if (scaleX != 1f || scaleY != 1f || rotation != 0f) {
                add(
                    ScaleAndRotateTransformation.Builder()
                        .setScale(scaleX, scaleY)
                        .setRotationDegrees(rotation)
                        .build()
                )
            }
        }

        // Manual adjustments (all normalized -1..1 in the DB).
        if (clip.adjustBrightness != 0f) add(Brightness(clip.adjustBrightness * 0.5f))
        if (clip.adjustContrast != 0f) add(Contrast(clip.adjustContrast * 0.6f))
        if (clip.adjustSaturation != 0f || clip.adjustHue != 0f) {
            add(
                HslAdjustment.Builder()
                    .adjustSaturation(clip.adjustSaturation * 60f)
                    .adjustHue(clip.adjustHue * 180f)
                    .build()
            )
        }
        if (clip.adjustTemperature != 0f || clip.adjustTint != 0f) {
            val t = clip.adjustTemperature * 0.15f
            val g = clip.adjustTint * 0.12f
            add(
                RgbAdjustment.Builder()
                    .setRedScale(1f + t)
                    .setGreenScale(1f + g)
                    .setBlueScale(1f - t)
                    .build()
            )
        }

        addAll(FilterLibrary.effectsFor(clip.filterId, clip.filterIntensity))

        if (includeTransform && clip.opacity < 1f &&
            transformKeyframes.none { it.property == KeyframeProperty.OPACITY }
        ) {
            add(AlphaScale(clip.opacity.coerceIn(0f, 1f)))
        }

        // Transition ramps run last so they act on the final look.
        if (context.headTransitionId != null && context.headWindowUs > 0) {
            addAll(Transitions.inEffects(context.headTransitionId, context.headWindowUs))
        }
        if (context.tailTransitionId != null &&
            context.tailWindowUs > 0 && context.itemDurationUs > 0
        ) {
            addAll(
                Transitions.outEffects(
                    context.tailTransitionId, context.itemDurationUs, context.tailWindowUs
                )
            )
        }
    }

    /**
     * Keyframed position/scale/rotation as one per-frame matrix, plus a
     * per-frame gain ramp for opacity keyframes. Keyframe times are relative
     * to the clip's start — exactly the item-local effect timestamps.
     */
    private fun keyframedTransformEffects(
        clip: ClipEntity,
        keyframes: List<KeyframeEntity>,
    ): List<Effect> {
        val byProperty = keyframes.groupBy { it.property }
        val flipX = if (clip.flipH) -1f else 1f
        val flipY = if (clip.flipV) -1f else 1f

        val effects = mutableListOf<Effect>(
            MatrixTransformation { presentationTimeUs ->
                val t = KeyframeEngine.transformAt(
                    byProperty, presentationTimeUs / 1000,
                    clip.positionX, clip.positionY, clip.scale,
                    clip.rotationDeg, clip.opacity, clip.volume,
                )
                Matrix().apply {
                    postScale(t.scale * flipX, t.scale * flipY)
                    postRotate(-t.rotationDeg)
                    // positionX/Y are 0..1 top-left origin; NDC is -1..1 y-up.
                    postTranslate((t.positionX - 0.5f) * 2f, -(t.positionY - 0.5f) * 2f)
                }
            }
        )

        if (byProperty.containsKey(KeyframeProperty.OPACITY)) {
            val opacityKfs = byProperty[KeyframeProperty.OPACITY].orEmpty()
            effects.add(
                RgbMatrix { presentationTimeUs, _ ->
                    val o = KeyframeEngine.valueAt(
                        opacityKfs, presentationTimeUs / 1000, clip.opacity
                    ).coerceIn(0f, 1f)
                    floatArrayOf(
                        o, 0f, 0f, 0f,
                        0f, o, 0f, 0f,
                        0f, 0f, o, 0f,
                        0f, 0f, 0f, 1f,
                    )
                }
            )
        }
        return effects
    }

    /**
     * Per-clip audio chain: speed (constant via Sonic, ramped via
     * SpeedChangingAudioProcessor) → voice pitch → echo → fades → gain.
     */
    fun audioProcessorsFor(clip: ClipEntity): List<AudioProcessor> = buildList {
        val voice = VOICE_EFFECTS.firstOrNull { it.id == clip.voiceEffectId }
        val curve = SpeedCurve.parse(clip.speedCurveJson)

        if (curve != null) {
            // Ramped speed: must mirror the video's SpeedChangeEffect provider.
            add(
                SpeedChangingAudioProcessor(
                    curve.toSpeedProvider(clip.sourceEndMs - clip.sourceStartMs)
                )
            )
            if (voice != null && voice.pitch != 1f) {
                add(SonicAudioProcessor().apply { setPitch(voice.pitch) })
            }
        } else if (clip.speed != 1f || (voice != null && voice.pitch != 1f)) {
            add(
                SonicAudioProcessor().apply {
                    if (clip.speed != 1f) setSpeed(clip.speed)
                    if (voice != null && voice.pitch != 1f) setPitch(voice.pitch)
                }
            )
        }
        if (voice?.echo == true) add(EchoAudioProcessor())

        if (clip.fadeInMs > 0 || clip.fadeOutMs > 0) {
            add(FadeAudioProcessor(clip.fadeInMs, clip.fadeOutMs, clip.timelineDurationMs))
        }

        if (clip.volume != 1f) {
            add(
                ChannelMixingAudioProcessor().apply {
                    putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1).scaleBy(clip.volume))
                    putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2).scaleBy(clip.volume))
                }
            )
        }
    }
}
