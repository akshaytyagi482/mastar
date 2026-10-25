package com.mastar.editor.engine.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import com.mastar.editor.engine.DeviceRenderProfile
import java.io.File
import java.security.MessageDigest

/**
 * Pre-extracts the incoming clip's head frames for a cross transition.
 * Rendering those frames as a plain bitmap overlay runs on the ordinary
 * single-input video graph — no multi-input compositor, so transitions
 * play on EVERY device (the multi-input path stalled some phones).
 */
object TransitionFrames {

    const val FPS = 20
    private const val JPEG_QUALITY = 82

    fun cacheDirFor(context: Context, sourceUri: String, sourceStartMs: Long, windowMs: Long): File {
        val maxDimension = DeviceRenderProfile.from(context).transitionLongSidePixels
        val key = MessageDigest.getInstance("SHA-1")
            .digest("$sourceUri|$sourceStartMs|$windowMs|$maxDimension".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "transitions"), key)
    }

    fun frameCount(windowMs: Long): Int = ((windowMs * FPS / 1000).toInt() + 1).coerceAtLeast(2)

    /** True when every frame for this window is already on disk. */
    fun isReady(context: Context, sourceUri: String, sourceStartMs: Long, windowMs: Long): Boolean {
        val dir = cacheDirFor(context, sourceUri, sourceStartMs, windowMs)
        val expected = frameCount(windowMs)
        return dir.isDirectory && (dir.listFiles()?.count { it.extension == "jpg" } ?: 0) >= expected
    }

    /**
     * Extracts and caches the head frames (blocking — call from IO).
     * Returns true on success; failures leave the transition as a hard cut.
     */
    fun ensure(context: Context, sourceUri: String, sourceStartMs: Long, windowMs: Long): Boolean {
        if (isReady(context, sourceUri, sourceStartMs, windowMs)) return true
        val maxDimension = DeviceRenderProfile.from(context).transitionLongSidePixels
        val dir = cacheDirFor(context, sourceUri, sourceStartMs, windowMs)
        dir.mkdirs()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(sourceUri))
            val count = frameCount(windowMs)
            for (i in 0 until count) {
                val timeMs = sourceStartMs + windowMs * i / (count - 1).coerceAtLeast(1)
                // API 27+ can ask the platform retriever to scale before
                // materializing the bitmap. Older devices keep the compatible
                // full-frame path, with only one frame held at a time.
                val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    runCatching {
                        retriever.getScaledFrameAtTime(
                            timeMs * 1000,
                            MediaMetadataRetriever.OPTION_CLOSEST,
                            maxDimension,
                            maxDimension,
                        )
                    }.getOrNull()
                } else {
                    null
                } ?: retriever.getFrameAtTime(
                    timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue
                var scaled: Bitmap? = null
                try {
                    val scaledFrame = downscale(frame, maxDimension)
                    scaled = scaledFrame
                    File(dir, "f_%03d.jpg".format(i)).outputStream().use { out ->
                        scaledFrame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    }
                } finally {
                    if (scaled !== frame) scaled?.recycle()
                    frame.recycle()
                }
            }
            isReady(context, sourceUri, sourceStartMs, windowMs) ||
                ensureFromStill(context, sourceUri, sourceStartMs, windowMs, maxDimension)
        } catch (e: Exception) {
            ensureFromStill(context, sourceUri, sourceStartMs, windowMs, maxDimension)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Photo clips: one decoded bitmap becomes every frame of the window. */
    private fun ensureFromStill(
        context: Context,
        sourceUri: String,
        sourceStartMs: Long,
        windowMs: Long,
        maxDimension: Int,
    ): Boolean = runCatching {
        val uri = Uri.parse(sourceUri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return false
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        // Decode near the cache's target size so a large phone photo does not
        // require a full-resolution ARGB bitmap just to produce small JPEGs.
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }
        val src = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(
                input,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.RGB_565
                },
            )
        } ?: return false
        var scaled: Bitmap? = null
        try {
            val scaledBitmap = downscale(src, maxDimension)
            scaled = scaledBitmap
            val dir = cacheDirFor(context, sourceUri, sourceStartMs, windowMs)
            dir.mkdirs()
            for (i in 0 until frameCount(windowMs)) {
                File(dir, "f_%03d.jpg".format(i)).outputStream().use { out ->
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
            }
        } finally {
            if (scaled !== src) scaled?.recycle()
            src.recycle()
        }
        isReady(context, sourceUri, sourceStartMs, windowMs)
    }.getOrDefault(false)

    private fun downscale(src: Bitmap, maxDimension: Int): Bitmap {
        val maxDim = maxOf(src.width, src.height)
        if (maxDim <= maxDimension) return src
        val scale = maxDimension.toFloat() / maxDim
        return Bitmap.createScaledBitmap(
            src, (src.width * scale).toInt(), (src.height * scale).toInt(), true
        )
    }
}
