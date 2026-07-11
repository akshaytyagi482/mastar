package com.mastar.editor.engine.effects

import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.AlphaScale
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.ScaleAndRotateTransformation
import com.mastar.editor.data.db.ClipEntity
import com.mastar.editor.engine.audio.EchoAudioProcessor
import com.mastar.editor.engine.audio.FadeAudioProcessor

/**
 * The single source of truth that turns a clip's persisted edit state into
 * Media3 effect chains. Preview (ExoPlayer.setVideoEffects) and export
 * (Transformer) both call this — guaranteeing WYSIWYG.
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

    /**
     * Per-clip video chain: transform (rotate/flip/scale) → manual color
     * adjustments → filter at intensity → opacity.
     */
    fun videoEffectsFor(clip: ClipEntity): List<Effect> = buildList {
        // Transform. Flip is a negative scale; Media3 normalizes output size.
        val scaleX = clip.scale * (if (clip.flipH) -1f else 1f)
        val scaleY = clip.scale * (if (clip.flipV) -1f else 1f)
        if (scaleX != 1f || scaleY != 1f || clip.rotationDeg != 0f) {
            add(
                ScaleAndRotateTransformation.Builder()
                    .setScale(scaleX, scaleY)
                    .setRotationDegrees(clip.rotationDeg)
                    .build()
            )
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
            // Warm shifts red up / blue down; tint shifts green vs magenta.
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

        if (clip.opacity < 1f) add(AlphaScale(clip.opacity.coerceIn(0f, 1f)))
    }

    /**
     * Per-clip audio chain: speed (pitch-preserving) → voice effect pitch →
     * echo → fades → volume gain.
     */
    fun audioProcessorsFor(clip: ClipEntity): List<AudioProcessor> = buildList {
        val voice = VOICE_EFFECTS.firstOrNull { it.id == clip.voiceEffectId }

        if (clip.speed != 1f || (voice != null && voice.pitch != 1f)) {
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
