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
 *
 * Chain order matters: COLOR effects run before the canvas Presentation;
 * the TRANSFORM matrix runs AFTER it (v0.6 ran transform first and the
 * canvas crop visually cancelled it — the "transform does nothing" bug).
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

    /** Pre-canvas color chain: adjustments → filter → opacity. */
    fun colorEffectsFor(
        clip: ClipEntity,
        keyframes: List<KeyframeEntity> = emptyList(),
    ): List<Effect> = buildList {
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

        val opacityKfs = keyframes.filter { it.property == KeyframeProperty.OPACITY }
        if (opacityKfs.isNotEmpty()) {
            val gain = FloatArray(16)
            add(
                RgbMatrix { presentationTimeUs, _ ->
                    val o = KeyframeEngine.valueAt(
                        opacityKfs, presentationTimeUs / 1000, clip.opacity
                    ).coerceIn(0f, 1f)
                    gain.fill(0f)
                    gain[0] = o; gain[5] = o; gain[10] = o; gain[15] = 1f
                    gain
                }
            )
        } else if (clip.opacity < 1f) {
            add(AlphaScale(clip.opacity.coerceIn(0f, 1f)))
        }
    }

    /**
     * Post-canvas transform: position + scale + rotation + flip as one
     * NDC matrix, animated when transform keyframes exist. The Matrix
     * instance is reused per frame — no per-frame allocation.
     */
    fun transformEffectFor(
        clip: ClipEntity,
        keyframes: List<KeyframeEntity> = emptyList(),
        /** Item time is shifted by this much when the head was trimmed. */
        timeOffsetMs: Long = 0,
    ): Effect? {
        val transformKfs = keyframes.filter {
            it.property == KeyframeProperty.POSITION_X ||
                it.property == KeyframeProperty.POSITION_Y ||
                it.property == KeyframeProperty.SCALE ||
                it.property == KeyframeProperty.ROTATION
        }
        val flipX = if (clip.flipH) -1f else 1f
        val flipY = if (clip.flipV) -1f else 1f

        if (transformKfs.isEmpty()) {
            val isIdentity = clip.positionX == 0.5f && clip.positionY == 0.5f &&
                clip.scale == 1f && clip.rotationDeg == 0f && !clip.flipH && !clip.flipV
            if (isIdentity) return null
            val matrix = Matrix().apply {
                postScale(clip.scale * flipX, clip.scale * flipY)
                postRotate(-clip.rotationDeg)
                postTranslate((clip.positionX - 0.5f) * 2f, -(clip.positionY - 0.5f) * 2f)
            }
            return MatrixTransformation { matrix }
        }

        val byProperty = transformKfs.groupBy { it.property }
        val matrix = Matrix()
        return MatrixTransformation { presentationTimeUs ->
            val t = KeyframeEngine.transformAt(
                byProperty, presentationTimeUs / 1000 + timeOffsetMs,
                clip.positionX, clip.positionY, clip.scale,
                clip.rotationDeg, clip.opacity, clip.volume,
            )
            matrix.reset()
            matrix.postScale(t.scale * flipX, t.scale * flipY)
            matrix.postRotate(-t.rotationDeg)
            matrix.postTranslate((t.positionX - 0.5f) * 2f, -(t.positionY - 0.5f) * 2f)
            matrix
        }
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
