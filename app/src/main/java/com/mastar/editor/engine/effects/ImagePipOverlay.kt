package com.mastar.editor.engine.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import com.mastar.editor.data.db.ClipEntity
import com.mastar.editor.data.db.KeyframeEntity
import com.mastar.editor.engine.keyframe.KeyframeEngine

/**
 * Photo PIP as a composition-level bitmap overlay. Runs on the ordinary
 * single-input video graph, so it works on EVERY device — no multi-input
 * compositor required (video PIP still uses the second sequence).
 * Placement (position/scale/opacity/rotation) is keyframable and evaluated
 * per output frame; outside the clip's window the overlay is fully
 * transparent.
 */
@UnstableApi
class ImagePipOverlay(
    context: Context,
    private val clip: ClipEntity,
    keyframes: List<KeyframeEntity>,
    private val outShortSide: Int,
) : BitmapOverlay() {

    private val keyframesByProperty = keyframes.groupBy { it.property }

    private val bitmap: Bitmap by lazy {
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(clip.sourceUri))?.use { input ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, opts)
                var sample = 1
                val maxDim = maxOf(opts.outWidth, opts.outHeight)
                while (maxDim / sample > MAX_DECODE_DIM) sample *= 2
                context.contentResolver.openInputStream(Uri.parse(clip.sourceUri))
                    ?.use { second ->
                        BitmapFactory.decodeStream(
                            second, null,
                            BitmapFactory.Options().apply { inSampleSize = sample },
                        )
                    }
            }
        }.getOrNull() ?: Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val timeMs = presentationTimeUs / 1000
        if (timeMs !in clip.timelineStartMs until clip.timelineEndMs) {
            return HIDDEN
        }
        val t = KeyframeEngine.transformAt(
            keyframesByProperty, timeMs - clip.timelineStartMs,
            clip.positionX, clip.positionY, clip.scale,
            clip.rotationDeg, clip.opacity, clip.volume,
        )
        // clip.scale=1 means "as wide as the canvas short side"; the overlay
        // texture is placed at its pixel size, so normalize by bitmap size.
        val pixelScale = t.scale * outShortSide / maxOf(bitmap.width, bitmap.height).toFloat()
        val ndcX = t.positionX * 2f - 1f
        val ndcY = -(t.positionY * 2f - 1f)
        return StaticOverlaySettings.Builder()
            .setAlphaScale(t.opacity.coerceIn(0f, 1f))
            .setScale(pixelScale, pixelScale)
            .setRotationDegrees(-t.rotationDeg)
            .setBackgroundFrameAnchor(ndcX, ndcY)
            .setOverlayFrameAnchor(0f, 0f)
            .build()
    }

    private companion object {
        const val MAX_DECODE_DIM = 1080
        val HIDDEN: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
    }
}
