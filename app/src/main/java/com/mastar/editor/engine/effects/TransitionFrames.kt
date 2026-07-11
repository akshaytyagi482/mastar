package com.mastar.editor.engine.effects

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
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
    private const val MAX_DIM = 720
    private const val JPEG_QUALITY = 82

    fun cacheDirFor(context: Context, sourceUri: String, sourceStartMs: Long, windowMs: Long): File {
        val key = MessageDigest.getInstance("SHA-1")
            .digest("$sourceUri|$sourceStartMs|$windowMs".toByteArray())
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
        val dir = cacheDirFor(context, sourceUri, sourceStartMs, windowMs)
        dir.mkdirs()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(sourceUri))
            val count = frameCount(windowMs)
            for (i in 0 until count) {
                val timeMs = sourceStartMs + windowMs * i / (count - 1).coerceAtLeast(1)
                val frame = retriever.getFrameAtTime(
                    timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: continue
                val scaled = downscale(frame)
                File(dir, "f_%03d.jpg".format(i)).outputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                if (scaled !== frame) scaled.recycle()
                frame.recycle()
            }
            isReady(context, sourceUri, sourceStartMs, windowMs) ||
                ensureFromStill(context, sourceUri, sourceStartMs, windowMs)
        } catch (e: Exception) {
            ensureFromStill(context, sourceUri, sourceStartMs, windowMs)
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
    ): Boolean = runCatching {
        val src = context.contentResolver.openInputStream(Uri.parse(sourceUri))
            ?.use { android.graphics.BitmapFactory.decodeStream(it) }
            ?: return false
        val scaled = downscale(src)
        val dir = cacheDirFor(context, sourceUri, sourceStartMs, windowMs)
        dir.mkdirs()
        for (i in 0 until frameCount(windowMs)) {
            File(dir, "f_%03d.jpg".format(i)).outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        }
        if (scaled !== src) scaled.recycle()
        src.recycle()
        isReady(context, sourceUri, sourceStartMs, windowMs)
    }.getOrDefault(false)

    private fun downscale(src: Bitmap): Bitmap {
        val maxDim = maxOf(src.width, src.height)
        if (maxDim <= MAX_DIM) return src
        val scale = MAX_DIM.toFloat() / maxDim
        return Bitmap.createScaledBitmap(
            src, (src.width * scale).toInt(), (src.height * scale).toInt(), true
        )
    }
}
