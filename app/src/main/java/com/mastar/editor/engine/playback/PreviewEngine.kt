package com.mastar.editor.engine.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.mastar.editor.data.db.ClipEntity
import com.mastar.editor.engine.effects.FilterLibrary

/**
 * Timeline preview built on Media3 ExoPlayer.
 *
 * The core trick of the whole app: we never cut files. Each [ClipEntity] is
 * turned into a MediaItem with a ClippingConfiguration — ExoPlayer seeks the
 * untouched source and plays only the [sourceStartMs, sourceEndMs] window.
 * Rebuilding the playlist after an edit is just swapping metadata, so even
 * a ₹12,000 phone re-renders the timeline instantly.
 */
@UnstableApi
class PreviewEngine(context: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setSeekBackIncrementMs(1000)
        .setSeekForwardIncrementMs(1000)
        .build()

    private var timelineClips: List<ClipEntity> = emptyList()

    init {
        // Per-clip color filters: swap the GPU effect chain as playback
        // crosses clip boundaries. Same effects as export — WYSIWYG.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                applyFilterForIndex(player.currentMediaItemIndex)
            }
        })
    }

    /**
     * Rebuilds the preview playlist from the main video track's clips.
     * Clips must be sorted by [ClipEntity.timelineStartMs].
     */
    fun setTimeline(clips: List<ClipEntity>) {
        timelineClips = clips
        val wasPlaying = player.isPlaying
        val items = clips.map { it.toMediaItem() }
        // Effects must be in place before prepare() on some Media3 versions.
        applyFilterForIndex(0)
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

    /** Maps the player's (item, position) back to absolute project-timeline ms. */
    fun currentTimelinePositionMs(): Long {
        val clip = timelineClips.getOrNull(player.currentMediaItemIndex) ?: return 0L
        return clip.timelineStartMs + player.currentPosition.coerceAtLeast(0)
    }

    fun play() = player.play()
    fun pause() = player.pause()

    fun release() = player.release()

    private fun applyFilterForIndex(index: Int) {
        val clip = timelineClips.getOrNull(index)
        // setVideoEffects is flagged unstable; never let a preview-effect
        // failure take down playback itself.
        runCatching { player.setVideoEffects(FilterLibrary.effectsFor(clip?.filterId)) }
    }

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
