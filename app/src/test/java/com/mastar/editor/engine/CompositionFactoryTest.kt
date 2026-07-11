package com.mastar.editor.engine

import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.mastar.editor.data.db.ClipEntity
import com.mastar.editor.data.db.ClipType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the CompositionPlayer contract that caused the v0.4 black-screen:
 * compositions must have NO sequence gaps and EVERY item needs durationUs.
 * If these fail, the preview on a real device will be black — so they gate
 * every build.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CompositionFactoryTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun videoClip(
        id: Long,
        startMs: Long,
        durationMs: Long = 3000,
        trackId: Long = 1,
        type: ClipType = ClipType.VIDEO,
    ) = ClipEntity(
        id = id,
        trackId = trackId,
        type = type,
        sourceUri = "file:///fake/video$id.mp4",
        sourceStartMs = 0,
        sourceEndMs = durationMs,
        sourceDurationMs = durationMs,
        timelineStartMs = startMs,
    )

    @Test
    fun `simple timeline has one sequence with all durations set`() {
        val layers = CompositionFactory.Layers(
            videoClips = listOf(videoClip(1, 0), videoClip(2, 3000)),
            overlayLanes = emptyList(),
            audioClips = emptyList(),
            textClips = emptyList(),
        )
        val composition = CompositionFactory.build(context, layers, 720, 1280)

        assertEquals(1, composition.sequences.size)
        assertEquals(2, composition.sequences[0].editedMediaItems.size)
        composition.sequences.flatMap { it.editedMediaItems }.forEach { item ->
            assertTrue(
                "every item needs durationUs (CompositionPlayer requirement)",
                item.durationUs > 0,
            )
        }
    }

    @Test
    fun `timeline hole is filled with real media, not a gap`() {
        val layers = CompositionFactory.Layers(
            // Second clip starts at 5000 -> 2000ms hole after the first.
            videoClips = listOf(videoClip(1, 0), videoClip(2, 5000)),
            overlayLanes = emptyList(),
            audioClips = emptyList(),
            textClips = emptyList(),
        )
        val composition = CompositionFactory.build(context, layers, 720, 1280)

        val items = composition.sequences[0].editedMediaItems
        assertEquals("clip + black filler + clip", 3, items.size)
        items.forEach { assertTrue(it.durationUs > 0) }
    }

    @Test
    fun `offset audio is positioned with generated silence`() {
        val layers = CompositionFactory.Layers(
            videoClips = listOf(videoClip(1, 0, durationMs = 10_000)),
            overlayLanes = emptyList(),
            audioClips = listOf(
                videoClip(9, startMs = 2000, durationMs = 4000, trackId = 2, type = ClipType.AUDIO)
            ),
            textClips = emptyList(),
        )
        val composition = CompositionFactory.build(context, layers, 720, 1280)

        assertEquals(2, composition.sequences.size)
        val audioItems = composition.sequences[1].editedMediaItems
        assertEquals("silence + audio clip", 2, audioItems.size)
        assertEquals(2_000_000, audioItems[0].durationUs)
        audioItems.forEach { assertTrue(it.durationUs > 0) }
    }

    @Test
    fun `pip overlay becomes a second video sequence with durations`() {
        val layers = CompositionFactory.Layers(
            videoClips = listOf(videoClip(1, 0, durationMs = 8000)),
            overlayLanes = listOf(
                listOf(videoClip(5, startMs = 1000, durationMs = 2000, trackId = 3))
            ),
            audioClips = emptyList(),
            textClips = emptyList(),
        )
        val composition = CompositionFactory.build(context, layers, 720, 1280)

        assertEquals(2, composition.sequences.size)
        val overlayItems = composition.sequences[1].editedMediaItems
        assertEquals("leading filler + overlay clip", 2, overlayItems.size)
        overlayItems.forEach { assertTrue(it.durationUs > 0) }
    }

    @Test
    fun `cross transition stays single-input and keeps the main lane contiguous`() {
        val a = videoClip(1, 0, durationMs = 5000)
            .copy(transitionId = "fade", transitionDurationMs = 600)
        // CapCut ripple: B overlaps A by the transition duration.
        val b = videoClip(2, 4400, durationMs = 5000)
        val layers = CompositionFactory.Layers(
            videoClips = listOf(a, b),
            overlayLanes = emptyList(),
            audioClips = emptyList(),
            textClips = emptyList(),
        )
        // The multi-input compositor froze some devices — transitions must
        // never require it (they render as bitmap overlays instead).
        assertTrue(!CompositionFactory.needsMultipleInputs(layers))

        val composition = CompositionFactory.build(context, layers, 720, 1280)
        assertEquals(1, composition.sequences.size)
        // A + head-trimmed B, contiguous (no filler despite the overlap).
        assertEquals(2, composition.sequences[0].editedMediaItems.size)
        composition.sequences.flatMap { it.editedMediaItems }
            .forEach { assertTrue(it.durationUs > 0) }
    }

    @Test
    fun `transition segments follow the actual clip overlap`() {
        val a = videoClip(1, 0, durationMs = 5000)
            .copy(transitionId = "fade", transitionDurationMs = 600)
        val b = videoClip(2, 4400, durationMs = 5000)

        val segments = CompositionFactory.transitionSegments(listOf(a, b))

        assertEquals(1, segments.size)
        assertEquals(4400, segments[0].windowStartMs)
        assertEquals(600, segments[0].windowMs)
        assertEquals(b.id, segments[0].clip.id)
    }

    @Test
    fun `ready transition frames attach a composition-level overlay`() {
        val a = videoClip(1, 0, durationMs = 5000)
            .copy(transitionId = "fade", transitionDurationMs = 600)
        val b = videoClip(2, 4400, durationMs = 5000)
        // Pretend the editor already extracted B's head frames.
        val dir = com.mastar.editor.engine.effects.TransitionFrames
            .cacheDirFor(context, b.sourceUri, b.sourceStartMs, 600)
        dir.mkdirs()
        repeat(com.mastar.editor.engine.effects.TransitionFrames.frameCount(600)) { i ->
            java.io.File(dir, "f_%03d.jpg".format(i)).writeBytes(byteArrayOf(0))
        }

        val layers = CompositionFactory.Layers(
            videoClips = listOf(a, b),
            overlayLanes = emptyList(),
            audioClips = emptyList(),
            textClips = emptyList(),
        )
        val composition = CompositionFactory.build(context, layers, 720, 1280)

        assertEquals(1, composition.sequences.size)
        assertTrue(
            "transition must render as a composition-level overlay",
            composition.effects.videoEffects.isNotEmpty(),
        )
    }

    @Test
    fun `single-clip animations build without extra sequences`() {
        val clip = videoClip(1, 0, durationMs = 5000).copy(
            animInId = "fade", animInDurationMs = 500,
            animOutId = "zoomin", animOutDurationMs = 500,
        )
        val layers = CompositionFactory.Layers(
            videoClips = listOf(clip),
            overlayLanes = emptyList(),
            audioClips = emptyList(),
            textClips = emptyList(),
        )
        val composition = CompositionFactory.build(context, layers, 720, 1280)

        assertEquals(1, composition.sequences.size)
        composition.sequences[0].editedMediaItems.forEach { assertTrue(it.durationUs > 0) }
    }

    @Test
    fun `flash transition stays a ramp with no overlap lane`() {
        val a = videoClip(1, 0, durationMs = 5000)
            .copy(transitionId = "flash", transitionDurationMs = 600)
        val b = videoClip(2, 5000, durationMs = 5000)
        val layers = CompositionFactory.Layers(
            videoClips = listOf(a, b),
            overlayLanes = emptyList(),
            audioClips = emptyList(),
            textClips = emptyList(),
        )
        assertTrue(!CompositionFactory.needsMultipleInputs(layers))
        val composition = CompositionFactory.build(context, layers, 720, 1280)
        assertEquals(1, composition.sequences.size)
    }

    @Test
    fun `empty timeline is rejected loudly`() {
        val layers = CompositionFactory.Layers(
            videoClips = emptyList(),
            overlayLanes = emptyList(),
            audioClips = emptyList(),
            textClips = emptyList(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            CompositionFactory.build(context, layers, 720, 1280)
        }
    }
}
