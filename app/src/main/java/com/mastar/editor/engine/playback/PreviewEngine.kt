package com.mastar.editor.engine.playback

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import com.mastar.editor.engine.CompositionFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Timeline preview built on Media3 CompositionPlayer: it plays the EXACT
 * Composition that the export encodes — per-clip filters, adjustments,
 * transforms, speed, volume, fades, voice effects, PIP overlays, and
 * burned-in text all render live. No effect swapping mid-playback (that was
 * v0.3's black-screen bug); every edit rebuilds the composition instead.
 */
@UnstableApi
class PreviewEngine(private val context: Context) {

    /**
     * The active player. Rebuilt (not mutated) on every timeline change —
     * CompositionPlayer is designed around one composition per instance.
     * The UI observes this flow and re-attaches the surface.
     */
    private val _player = MutableStateFlow<Player?>(null)
    val playerFlow: StateFlow<Player?> = _player
    val player: Player? get() = _player.value

    private var currentLayers: CompositionFactory.Layers? = null
    private var canvasWidth = 1080
    private var canvasHeight = 1920

    /** Preview renders at reduced resolution to stay cool on budget phones. */
    private fun previewSize(): Pair<Int, Int> {
        val shortSide = minOf(canvasWidth, canvasHeight)
        val scale = if (shortSide > 720) 720f / shortSide else 1f
        return Pair(
            ((canvasWidth * scale).toInt() and -2),
            ((canvasHeight * scale).toInt() and -2),
        )
    }

    fun setCanvas(width: Int, height: Int) {
        canvasWidth = width
        canvasHeight = height
    }

    /** Rebuilds the preview from the timeline, keeping the playhead position. */
    fun setTimeline(layers: CompositionFactory.Layers) {
        currentLayers = layers
        rebuild(layers, includeOverlayTrack = true)
    }

    private fun rebuild(layers: CompositionFactory.Layers, includeOverlayTrack: Boolean) {
        val previousPosition = _player.value?.currentPosition ?: 0L
        releasePlayer()

        if (layers.videoClips.isEmpty()) return

        val (w, h) = previewSize()
        val newPlayer = CompositionPlayer.Builder(context).build()
        newPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // A device-specific compositor failure must not brick the
                // preview: retry once without the PIP layer.
                if (includeOverlayTrack && layers.overlayClips.isNotEmpty()) {
                    rebuild(layers, includeOverlayTrack = false)
                }
            }
        })

        runCatching {
            newPlayer.setComposition(
                CompositionFactory.build(layers, w, h, includeOverlayTrack)
            )
            newPlayer.prepare()
            newPlayer.seekTo(previousPosition)
            _player.value = newPlayer
        }.onFailure {
            newPlayer.release()
            if (includeOverlayTrack && layers.overlayClips.isNotEmpty()) {
                rebuild(layers, includeOverlayTrack = false)
            }
        }
    }

    /** Composition time == project-timeline time: seeks map 1:1. */
    fun seekTo(timelineMs: Long) {
        _player.value?.seekTo(timelineMs.coerceAtLeast(0))
    }

    fun currentTimelinePositionMs(): Long = _player.value?.currentPosition ?: 0L

    val isPlaying: Boolean get() = _player.value?.isPlaying == true

    fun play() {
        _player.value?.play()
    }

    fun pause() {
        _player.value?.pause()
    }

    fun togglePlayback() {
        val p = _player.value ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    private fun releasePlayer() {
        _player.value?.release()
        _player.value = null
    }

    fun release() = releasePlayer()
}
