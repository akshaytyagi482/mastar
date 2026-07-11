package com.mastar.editor.engine.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import java.io.File

/**
 * A cross transition rendered as pre-extracted frames of the incoming clip,
 * composited over the outgoing clip with the style's animation. Runs on the
 * ordinary single-input graph — reliable on every device, identical in
 * preview and export.
 */
@UnstableApi
class FrameSequenceOverlay(
    context: Context,
    sourceUri: String,
    sourceStartMs: Long,
    private val styleId: String,
    private val windowStartMs: Long,
    private val windowMs: Long,
    private val outWidth: Int,
    private val outHeight: Int,
) : BitmapOverlay() {

    private val frames: Array<File> =
        TransitionFrames.cacheDirFor(context, sourceUri, sourceStartMs, windowMs)
            .listFiles { f -> f.extension == "jpg" }
            ?.sortedBy { it.name }
            ?.toTypedArray()
            ?: emptyArray()

    private var lastIndex = -1
    private var lastBitmap: Bitmap? = null
    private val blank = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    private fun progressAt(timeMs: Long): Float =
        ((timeMs - windowStartMs).toFloat() / windowMs).coerceIn(0f, 1f)

    private fun inWindow(timeMs: Long): Boolean =
        frames.isNotEmpty() && timeMs in windowStartMs until (windowStartMs + windowMs)

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val timeMs = presentationTimeUs / 1000
        if (!inWindow(timeMs)) return lastBitmap ?: blank
        val index = (progressAt(timeMs) * (frames.size - 1)).toInt()
            .coerceIn(0, frames.size - 1)
        if (index != lastIndex) {
            val decoded = runCatching {
                BitmapFactory.decodeFile(frames[index].absolutePath)
            }.getOrNull()
            if (decoded != null) {
                lastBitmap?.recycle()
                lastBitmap = decoded
                lastIndex = index
            }
        }
        return lastBitmap ?: blank
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val timeMs = presentationTimeUs / 1000
        val bmp = lastBitmap
        if (!inWindow(timeMs) || bmp == null) return HIDDEN
        val p = progressAt(timeMs)
        // Cover the whole canvas at the frame's pixel size.
        val cover = maxOf(
            outWidth.toFloat() / bmp.width,
            outHeight.toFloat() / bmp.height,
        )
        val b = StaticOverlaySettings.Builder()
        when (styleId) {
            "slideleft" -> b.setAlphaScale(1f)
                .setScale(cover, cover)
                .setBackgroundFrameAnchor(2f * (1f - p), 0f)
            "slideright" -> b.setAlphaScale(1f)
                .setScale(cover, cover)
                .setBackgroundFrameAnchor(-2f * (1f - p), 0f)
            "zoomin" -> {
                val sc = cover * (0.4f + 0.6f * p)
                b.setAlphaScale(p).setScale(sc, sc)
            }
            "spin" -> {
                val sc = cover * (0.3f + 0.7f * p)
                b.setAlphaScale(p).setScale(sc, sc).setRotationDegrees(180f * (1f - p))
            }
            else -> b.setAlphaScale(p).setScale(cover, cover) // cross fade
        }
        return b.build()
    }

    private companion object {
        val HIDDEN: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
    }
}
