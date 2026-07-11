package com.mastar.editor.engine.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.PreviewingMultipleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer
import com.mastar.editor.engine.CompositionFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Timeline preview built on Media3 CompositionPlayer: it plays the EXACT
 * Composition that the export encodes. Constraints honored (each was a
 * v0.4 black-screen cause):
 *  - a CompositionPlayer accepts exactly one composition → rebuild per edit
 *  - no sequence gaps → CompositionFactory inserts real filler media
 *  - every item needs durationUs → sourced from ClipEntity.sourceDurationMs
 *  - PIP needs the multiple-input video graph
 */
@UnstableApi
class PreviewEngine(private val context: Context) {

    private val _player = MutableStateFlow<Player?>(null)
    val playerFlow: StateFlow<Player?> = _player
    val player: Player? get() = _player.value

    /** Human-readable preview failure, surfaced in the UI instead of silence. */
    private val _error = MutableStateFlow<String?>(null)
    val errorFlow: StateFlow<String?> = _error

    private var canvasWidth = 1080
    private var canvasHeight = 1920
    private var lastSeekUptimeMs = 0L

    /** Preview renders at reduced resolution to stay cool on budget phones. */
    private fun previewSize(): Pair<Int, Int> {
        val shortSide = minOf(canvasWidth, canvasHeight)
        val scale = if (shortSide > 540) 540f / shortSide else 1f
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
        rebuild(layers, includeOverlayTrack = true)
    }

    private fun rebuild(layers: CompositionFactory.Layers, includeOverlayTrack: Boolean) {
        val previousPosition = _player.value?.currentPosition ?: 0L
        releasePlayer()
        _error.value = null

        if (layers.videoClips.isEmpty()) return

        val (w, h) = previewSize()
        val needsMultiInput =
            includeOverlayTrack && CompositionFactory.needsMultipleInputs(layers)
        val builder = CompositionPlayer.Builder(context)
        if (needsMultiInput) {
            // Photo PIP rides the normal graph via bitmap overlays; only
            // VIDEO PIP needs the multi-input GL graph.
            builder.setPreviewingVideoGraphFactory(PreviewingMultipleInputVideoGraph.Factory())
        }
        val newPlayer = builder.build()

        newPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // A device-specific compositor failure must not brick the
                // preview: retry once without the video-PIP layer — and SAY so.
                if (needsMultiInput) {
                    rebuild(layers, includeOverlayTrack = false)
                    _error.value =
                        "Video overlay preview isn't supported on this device — it will still export"
                } else {
                    _error.value = "Preview error: ${error.errorCodeName}"
                }
            }
        })

        runCatching {
            newPlayer.setComposition(
                CompositionFactory.build(context, layers, w, h, includeOverlayTrack)
            )
            // Loop at the timeline end instead of running past it
            // (CompositionPlayer supports REPEAT_MODE_ALL or OFF only).
            newPlayer.repeatMode = Player.REPEAT_MODE_ALL
            newPlayer.prepare()
            newPlayer.seekTo(previousPosition)
            _player.value = newPlayer
        }.onFailure { failure ->
            newPlayer.release()
            if (needsMultiInput) {
                rebuild(layers, includeOverlayTrack = false)
                _error.value =
                    "Video overlay preview isn't supported on this device — it will still export"
            } else {
                _error.value = "Preview error: ${failure.message}"
            }
        }
    }

    /**
     * Composition time == project-timeline time. Scrub seeks are throttled:
     * CompositionPlayer re-primes its pipeline per seek, so flooding it
     * during a drag stalls rendering.
     */
    fun seekTo(timelineMs: Long, force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastSeekUptimeMs < 150) return
        lastSeekUptimeMs = now
        _player.value?.seekTo(timelineMs.coerceAtLeast(0))
    }

    fun currentTimelinePositionMs(): Long = _player.value?.currentPosition ?: 0L

    val isPlaying: Boolean get() = _player.value?.isPlaying == true

    fun pause() {
        _player.value?.pause()
    }

    /** Play from an exact playhead position (guarantees UI/player sync). */
    fun togglePlayback(fromMs: Long) {
        val p = _player.value ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            seekTo(fromMs, force = true)
            p.play()
        }
    }

    private fun releasePlayer() {
        _player.value?.release()
        _player.value = null
    }

    fun release() = releasePlayer()
}
