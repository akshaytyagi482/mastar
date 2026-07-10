package com.mastar.editor.engine.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.mastar.editor.data.db.ClipEntity

/**
 * Timeline preview built on Media3 ExoPlayer.
 *
 * The core trick of the whole app: we never cut files. Each [ClipEntity] is
 * turned into a MediaItem with a ClippingConfiguration — ExoPlayer seeks the
 * untouched source and plays only the [sourceStartMs, sourceEndMs] window.
 * Rebuilding the playlist after an edit is just swapping metadata, so even
 * a ₹12,000 phone re-renders the timeline instantly.
 */
class PreviewEngine(context: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setSeekBackIncrementMs(1000)
        .setSeekForwardIncrementMs(1000)
        .build()

    /**
     * Rebuilds the preview playlist from the main video track's clips.
     * Clips must be sorted by [ClipEntity.timelineStartMs].
     */
    fun setTimeline(clips: List<ClipEntity>) {
        val wasPlaying = player.isPlaying
        val items = clips.map { it.toMediaItem() }
        player.setMediaItems(items)
        player.prepare()
        player.playWhenReady = wasPlaying
    }

    /** Raw speed change for the whole preview (per-clip speed is applied at export). */
    fun setSpeed(speed: Float) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    /** Seeks to an absolute project-timeline position across clip boundaries. */
    fun seekToTimeline(clips: List<ClipEntity>, timelineMs: Long) {
        var index = 0
        for ((i, clip) in clips.withIndex()) {
            if (timelineMs < clip.timelineEndMs) {
                index = i
                break
            }
            index = i
        }
        val clip = clips.getOrNull(index) ?: return
        val offsetInClip = (timelineMs - clip.timelineStartMs).coerceAtLeast(0)
        player.seekTo(index, offsetInClip)
    }

    fun play() = player.play()
    fun pause() = player.pause()

    fun release() = player.release()

    private fun ClipEntity.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setUri(Uri.parse(sourceUri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(sourceStartMs)
                    .setEndPositionMs(sourceEndMs)
                    .build()
            )
            .build()
}
