package com.mastar.editor.ui.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.media3.common.util.UnstableApi
import com.mastar.editor.MastarApp
import com.mastar.editor.data.db.ClipEntity
import com.mastar.editor.data.db.ClipType
import com.mastar.editor.data.db.EasingType
import com.mastar.editor.data.db.KeyframeEntity
import com.mastar.editor.data.db.KeyframeProperty
import com.mastar.editor.data.db.ProjectWithTracks
import com.mastar.editor.data.db.TrackType
import com.mastar.editor.engine.CompositionFactory
import com.mastar.editor.engine.export.ExportEngine
import com.mastar.editor.engine.export.GalleryPublisher
import com.mastar.editor.engine.keyframe.KeyframeEngine
import com.mastar.editor.engine.playback.PreviewEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** Long-press actions on a timeline clip. */
enum class ClipMenuAction {
    RENAME, DUPLICATE, LOCK, MUTE, HIDE,
    SELECT_ADD, GROUP, UNGROUP, RIPPLE_DELETE, DELETE,
}

@UnstableApi
class EditorViewModel(
    application: Application,
    private val projectId: Long,
) : AndroidViewModel(application) {

    private val repository = (application as MastarApp).projectRepository

    val previewEngine = PreviewEngine(application)
    private val exportEngine = ExportEngine(application)

    val project: StateFlow<ProjectWithTracks?> =
        repository.observeProject(projectId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val keyframes: StateFlow<List<KeyframeEntity>> =
        repository.observeKeyframesForProject(projectId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectedClipId = MutableStateFlow<Long?>(null)
    val selectedClipId: StateFlow<Long?> = _selectedClipId

    /** Multi-selection (long-press → Select); always includes the primary. */
    private val _multiSelection = MutableStateFlow<Set<Long>>(emptySet())
    val multiSelection: StateFlow<Set<Long>> = _multiSelection

    /** Clip awaiting a rename dialog. */
    private val _renameTarget = MutableStateFlow<ClipEntity?>(null)
    val renameTarget: StateFlow<ClipEntity?> = _renameTarget

    private val _exportState = MutableStateFlow<ExportEngine.State>(ExportEngine.State.Idle)
    val exportState: StateFlow<ExportEngine.State> = _exportState

    /** Gallery path of the last export, e.g. "Movies/Mastar/Mastar_....mp4". */
    private val _exportLocation = MutableStateFlow<String?>(null)
    val exportLocation: StateFlow<String?> = _exportLocation

    // CapCut-style undo/redo: every edit snapshots the full clip list (tiny —
    // it's metadata rows, not media). Restore rewrites the DB in one txn.
    private val undoStack = ArrayDeque<List<ClipEntity>>()
    private val redoStack = ArrayDeque<List<ClipEntity>>()
    private val _canUndo = MutableStateFlow(false)
    private val _canRedo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo
    val canRedo: StateFlow<Boolean> = _canRedo

    private var lastLayers: CompositionFactory.Layers? = null
    private var lastCanvas: Pair<Int, Int>? = null

    init {
        // The DB is the single source of truth: any clip OR keyframe change
        // lands here and rebuilds the preview composition once. collectLatest
        // + delay debounces edit bursts (drag commits, auto-key sweeps) so
        // the player isn't rebuilt more than ~6x/second.
        viewModelScope.launch {
            combine(project, keyframes) { p, kfs -> p to kfs }.collectLatest { (p, kfs) ->
                p ?: return@collectLatest
                val canvas = p.project.canvasWidth to p.project.canvasHeight
                val layers = layersFrom(p, kfs)
                if (layers != lastLayers || canvas != lastCanvas) {
                    kotlinx.coroutines.delay(160)
                    lastLayers = layers
                    lastCanvas = canvas
                    previewEngine.setCanvas(canvas.first, canvas.second)
                    previewEngine.setTimeline(layers)
                }
            }
        }
    }

    private fun layersFrom(
        p: ProjectWithTracks,
        kfs: List<KeyframeEntity> = keyframes.value,
    ): CompositionFactory.Layers {
        fun clipsOf(type: TrackType) = p.tracks
            .filter { it.track.type == type }
            .flatMap { it.clips }
            .filter { !it.hidden }
            .sortedBy { it.timelineStartMs }
        val overlayLanes = p.tracks
            .filter { it.track.type == TrackType.OVERLAY }
            .sortedBy { it.track.zOrder }
            .map { lane ->
                lane.clips.filter { !it.hidden }.sortedBy { it.timelineStartMs }
            }
            .filter { it.isNotEmpty() }
        return CompositionFactory.Layers(
            videoClips = clipsOf(TrackType.VIDEO),
            overlayLanes = overlayLanes,
            audioClips = clipsOf(TrackType.AUDIO),
            textClips = clipsOf(TrackType.TEXT),
            filterClips = clipsOf(TrackType.FILTER),
            keyframesByClip = kfs.groupBy { it.clipId },
        )
    }

    val projectDurationMs: Long
        get() = allClips().maxOfOrNull { it.timelineEndMs } ?: 0L

    fun selectedClip(): ClipEntity? =
        _selectedClipId.value?.let { id -> allClips().firstOrNull { it.id == id } }

    fun clipById(clipId: Long): ClipEntity? = allClips().firstOrNull { it.id == clipId }

    fun isOverlayClip(clipId: Long): Boolean =
        project.value?.tracks
            ?.filter { it.track.type == TrackType.OVERLAY }
            ?.any { lane -> lane.clips.any { it.id == clipId } } == true

    fun selectClip(clipId: Long?) {
        _selectedClipId.value = clipId
        if (clipId == null) _multiSelection.value = emptySet()
    }

    /** Keyframes of the selected clip, for panel + timeline diamonds. */
    fun keyframesForSelected(): List<KeyframeEntity> =
        _selectedClipId.value?.let { id -> keyframes.value.filter { it.clipId == id } }.orEmpty()

    fun keyframesFor(clipId: Long): List<KeyframeEntity> =
        keyframes.value.filter { it.clipId == clipId }

    private fun snapshotForUndo() {
        undoStack.addLast(allClips())
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        refreshUndoState()
    }

    private fun refreshUndoState() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    fun undo() {
        val snapshot = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(allClips())
        restore(snapshot)
    }

    fun redo() {
        val snapshot = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(allClips())
        restore(snapshot)
    }

    private fun restore(snapshot: List<ClipEntity>) {
        viewModelScope.launch {
            repository.replaceAllClips(projectId, snapshot)
            if (snapshot.none { it.id == _selectedClipId.value }) _selectedClipId.value = null
            refreshUndoState()
        }
    }

    fun addVideoClip(
        sourceUri: String,
        sourceDurationMs: Long,
        sourceWidth: Int = 0,
        sourceHeight: Int = 0,
    ) {
        viewModelScope.launch {
            val snapshot = project.value ?: return@launch
            val videoTrack =
                snapshot.tracks.firstOrNull { it.track.type == TrackType.VIDEO } ?: return@launch
            snapshotForUndo()
            val appendAtMs = videoTrack.clips.maxOfOrNull { it.timelineEndMs } ?: 0L
            repository.addClip(
                trackId = videoTrack.track.id,
                type = ClipType.VIDEO,
                sourceUri = sourceUri,
                sourceDurationMs = sourceDurationMs,
                timelineStartMs = appendAtMs,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
            )
        }
    }

    /**
     * PIP layer: video or photo floating over the main track. Lanes stack
     * automatically — overlapping additions get a fresh lane (video over
     * video over video, no manual track management).
     */
    fun addPipClip(
        sourceUri: String,
        isImage: Boolean,
        sourceDurationMs: Long,
        atMs: Long,
        sourceWidth: Int = 0,
        sourceHeight: Int = 0,
    ) {
        viewModelScope.launch {
            snapshotForUndo()
            val durationOnTimeline = sourceDurationMs
            val trackId = repository.findOrCreatePipLane(projectId, atMs, durationOnTimeline)
            repository.addPipClip(
                trackId = trackId,
                type = if (isImage) ClipType.IMAGE else ClipType.VIDEO,
                sourceUri = sourceUri,
                sourceDurationMs = sourceDurationMs,
                timelineStartMs = atMs,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
            )
        }
    }

    fun addAudioClip(sourceUri: String, sourceDurationMs: Long, atMs: Long) {
        viewModelScope.launch {
            snapshotForUndo()
            val trackId = repository.ensureTrack(projectId, TrackType.AUDIO)
            repository.addClip(
                trackId = trackId,
                type = ClipType.AUDIO,
                sourceUri = sourceUri,
                sourceDurationMs = sourceDurationMs,
                timelineStartMs = atMs,
            )
        }
    }

    /** Photo on the main track (plays as a still for [durationMs]). */
    fun addImageClip(sourceUri: String, atMs: Long? = null, durationMs: Long = 3000L) {
        viewModelScope.launch {
            val snapshot = project.value ?: return@launch
            val videoTrack =
                snapshot.tracks.firstOrNull { it.track.type == TrackType.VIDEO } ?: return@launch
            snapshotForUndo()
            val startMs = atMs ?: (videoTrack.clips.maxOfOrNull { it.timelineEndMs } ?: 0L)
            repository.addClip(
                trackId = videoTrack.track.id,
                type = ClipType.IMAGE,
                sourceUri = sourceUri,
                sourceDurationMs = durationMs,
                timelineStartMs = startMs,
            )
        }
    }

    /** CapCut "Extract audio": pull the soundtrack out of a video file. */
    fun extractAudio(sourceUri: String, sourceDurationMs: Long, atMs: Long) =
        addAudioClip(sourceUri, sourceDurationMs, atMs)

    fun duplicateSelectedClip() {
        viewModelScope.launch {
            val clip = selectedClip() ?: return@launch
            snapshotForUndo()
            repository.duplicateClip(clip)
            repository.resolveTrackOverlaps(projectId, clip.trackId)
        }
    }

    fun addTextClip(payloadJson: String, atMs: Long) {
        viewModelScope.launch {
            snapshotForUndo()
            val trackId = repository.ensureTrack(projectId, TrackType.TEXT)
            repository.addOverlayClip(
                trackId = trackId,
                type = ClipType.TEXT,
                payload = payloadJson,
                timelineStartMs = atMs,
                durationMs = DEFAULT_OVERLAY_DURATION_MS,
            )
        }
    }

    fun addStickerClip(lottieAsset: String, atMs: Long) {
        viewModelScope.launch {
            snapshotForUndo()
            val trackId = repository.ensureTrack(projectId, TrackType.STICKER)
            repository.addOverlayClip(
                trackId = trackId,
                type = ClipType.STICKER,
                payload = lottieAsset,
                timelineStartMs = atMs,
                durationMs = DEFAULT_OVERLAY_DURATION_MS,
            )
        }
    }

    /** Persists an edit (speed/volume/filter/transform/move) on a clip. */
    fun updateClip(clipId: Long, transform: (ClipEntity) -> ClipEntity) {
        viewModelScope.launch {
            val clip = allClips().firstOrNull { it.id == clipId } ?: return@launch
            if (clip.locked) return@launch
            snapshotForUndo()
            repository.updateClip(transform(clip))
        }
    }

    /** Group-aware move with insert-mode overlap resolution. */
    fun moveClip(clipId: Long, newTimelineStartMs: Long) {
        viewModelScope.launch {
            val clip = allClips().firstOrNull { it.id == clipId } ?: return@launch
            if (clip.locked) return@launch
            snapshotForUndo()
            val delta = newTimelineStartMs - clip.timelineStartMs
            if (clip.groupId != null) {
                repository.moveGroup(projectId, clip.groupId, delta)
            } else {
                repository.updateClip(clip.copy(timelineStartMs = newTimelineStartMs))
            }
            repository.resolveTrackOverlaps(projectId, clip.trackId)
        }
    }

    fun trimClip(clipId: Long, newSourceStartMs: Long, newSourceEndMs: Long, newTimelineStartMs: Long) {
        updateClip(clipId) {
            it.copy(
                sourceStartMs = newSourceStartMs,
                sourceEndMs = newSourceEndMs,
                timelineStartMs = newTimelineStartMs,
            )
        }
    }

    fun splitSelectedClipAtPlayhead(playheadMs: Long) {
        viewModelScope.launch {
            val clip = selectedClip() ?: return@launch
            if (clip.locked) return@launch
            snapshotForUndo()
            repository.splitClipAt(clip, playheadMs)
        }
    }

    fun deleteSelectedClip() {
        viewModelScope.launch {
            val clip = selectedClip() ?: return@launch
            if (clip.locked) return@launch
            snapshotForUndo()
            repository.deleteClip(clip.id)
            _selectedClipId.value = null
        }
    }

    /** Long-press menu dispatcher. */
    fun onClipMenuAction(clipId: Long, action: ClipMenuAction) {
        val clip = clipById(clipId) ?: return
        when (action) {
            ClipMenuAction.RENAME -> _renameTarget.value = clip
            ClipMenuAction.DUPLICATE -> {
                _selectedClipId.value = clipId
                duplicateSelectedClip()
            }
            ClipMenuAction.LOCK -> viewModelScope.launch {
                snapshotForUndo()
                repository.updateClip(clip.copy(locked = !clip.locked))
            }
            ClipMenuAction.MUTE -> updateClip(clipId) { it.copy(muted = !it.muted) }
            ClipMenuAction.HIDE -> viewModelScope.launch {
                snapshotForUndo()
                repository.updateClip(clip.copy(hidden = !clip.hidden))
            }
            ClipMenuAction.SELECT_ADD -> {
                _selectedClipId.value = clipId
                _multiSelection.value = _multiSelection.value + clipId
            }
            ClipMenuAction.GROUP -> viewModelScope.launch {
                val ids = _multiSelection.value + clipId
                if (ids.size < 2) return@launch
                snapshotForUndo()
                repository.setGroup(ids, System.currentTimeMillis())
                _multiSelection.value = emptySet()
            }
            ClipMenuAction.UNGROUP -> viewModelScope.launch {
                val gid = clip.groupId ?: return@launch
                snapshotForUndo()
                val members = allClips().filter { it.groupId == gid }.map { it.id }
                repository.setGroup(members, null)
            }
            ClipMenuAction.RIPPLE_DELETE -> viewModelScope.launch {
                if (clip.locked) return@launch
                snapshotForUndo()
                repository.rippleDelete(projectId, clip)
                if (_selectedClipId.value == clipId) _selectedClipId.value = null
            }
            ClipMenuAction.DELETE -> {
                _selectedClipId.value = clipId
                deleteSelectedClip()
            }
        }
    }

    fun renameClip(clipId: Long, name: String) {
        _renameTarget.value = null
        if (name.isBlank()) return
        updateClip(clipId) { it.copy(displayName = name.trim()) }
    }

    fun dismissRename() {
        _renameTarget.value = null
    }

    /**
     * Drops transform keyframes at the playhead, capturing the clip's
     * current (possibly already-keyframed) values — CapCut's diamond.
     */
    fun addKeyframeAtPlayhead(playheadMs: Long) {
        viewModelScope.launch {
            val clip = selectedClip() ?: return@launch
            val timeMs = (playheadMs - clip.timelineStartMs)
                .coerceIn(0, clip.timelineDurationMs)
            val existing = keyframesFor(clip.id).groupBy { it.property }
            val current = KeyframeEngine.transformAt(
                existing, timeMs,
                clip.positionX, clip.positionY, clip.scale,
                clip.rotationDeg, clip.opacity, clip.volume,
            )
            val values = mapOf(
                KeyframeProperty.POSITION_X to current.positionX,
                KeyframeProperty.POSITION_Y to current.positionY,
                KeyframeProperty.SCALE to current.scale,
                KeyframeProperty.ROTATION to current.rotationDeg,
                KeyframeProperty.OPACITY to current.opacity,
            )
            for ((property, value) in values) {
                // Replace any diamond already sitting at this time.
                existing[property]?.firstOrNull { kf -> kf.timeMs == timeMs }?.let {
                    repository.deleteKeyframe(it.id)
                }
                repository.addKeyframe(
                    KeyframeEntity(
                        clipId = clip.id,
                        property = property,
                        timeMs = timeMs,
                        value = value,
                        easing = EasingType.EASE_IN_OUT,
                    )
                )
            }
        }
    }

    /** Removes all keyframes at one diamond time on the selected clip. */
    fun removeKeyframesAt(timeMs: Long) {
        viewModelScope.launch {
            keyframesForSelected()
                .filter { it.timeMs == timeMs }
                .forEach { repository.deleteKeyframe(it.id) }
        }
    }

    /** Sets the interpolation curve leaving the diamond at [timeMs]. */
    fun setKeyframeEasing(timeMs: Long, easing: EasingType) {
        viewModelScope.launch {
            keyframesForSelected()
                .filter { it.timeMs == timeMs }
                .forEach { repository.updateKeyframe(it.copy(easing = easing)) }
        }
    }

    /**
     * CapCut auto-keying: when the clip already has keyframes, changing a
     * transform value WRITES a diamond at the playhead instead of (silently
     * ignored) static fields. Without keyframes it stays a static edit.
     */
    fun setClipTransform(
        clipId: Long,
        playheadMs: Long,
        values: Map<KeyframeProperty, Float>,
    ) {
        viewModelScope.launch {
            val clip = clipById(clipId) ?: return@launch
            if (clip.locked) return@launch
            val existing = keyframesFor(clipId)
            if (existing.isEmpty()) {
                snapshotForUndo()
                repository.updateClip(
                    clip.copy(
                        positionX = values[KeyframeProperty.POSITION_X] ?: clip.positionX,
                        positionY = values[KeyframeProperty.POSITION_Y] ?: clip.positionY,
                        scale = values[KeyframeProperty.SCALE] ?: clip.scale,
                        rotationDeg = values[KeyframeProperty.ROTATION] ?: clip.rotationDeg,
                        opacity = values[KeyframeProperty.OPACITY] ?: clip.opacity,
                    )
                )
            } else {
                val timeMs = (playheadMs - clip.timelineStartMs)
                    .coerceIn(0, clip.timelineDurationMs)
                for ((property, value) in values) {
                    existing.firstOrNull { it.property == property && it.timeMs == timeMs }
                        ?.let { repository.deleteKeyframe(it.id) }
                    repository.addKeyframe(
                        KeyframeEntity(
                            clipId = clipId,
                            property = property,
                            timeMs = timeMs,
                            value = value,
                            easing = EasingType.EASE_IN_OUT,
                        )
                    )
                }
            }
        }
    }

    /** Transform values at the playhead (keyframe-aware) for panel display. */
    fun transformValuesAt(clipId: Long, playheadMs: Long): KeyframeEngine.Transform? {
        val clip = clipById(clipId) ?: return null
        val kfs = keyframesFor(clipId).groupBy { it.property }
        return KeyframeEngine.transformAt(
            kfs, (playheadMs - clip.timelineStartMs).coerceIn(0, clip.timelineDurationMs),
            clip.positionX, clip.positionY, clip.scale,
            clip.rotationDeg, clip.opacity, clip.volume,
        )
    }

    /** CapCut-style filter LAYER: grades everything under it for its range. */
    fun addFilterLayer(filterId: String, atMs: Long) {
        viewModelScope.launch {
            snapshotForUndo()
            val trackId = repository.ensureTrack(projectId, TrackType.FILTER)
            repository.addFilterLayerClip(
                trackId = trackId,
                filterId = filterId,
                timelineStartMs = atMs,
                durationMs = DEFAULT_OVERLAY_DURATION_MS,
            )
        }
    }

    fun freezeFrame(playheadMs: Long, outputDir: File) {
        val clip = selectedClip() ?: return
        if (clip.type != ClipType.VIDEO) return
        if (playheadMs !in clip.timelineStartMs until clip.timelineEndMs) return

        viewModelScope.launch(Dispatchers.IO) {
            val sourceMs = clip.sourceStartMs +
                ((playheadMs - clip.timelineStartMs) * clip.speed).toLong()
            val retriever = android.media.MediaMetadataRetriever()
            val bitmap = try {
                retriever.setDataSource(
                    getApplication<Application>(),
                    android.net.Uri.parse(clip.sourceUri),
                )
                retriever.getFrameAtTime(sourceMs * 1000)
            } catch (e: Exception) {
                null
            } finally {
                retriever.release()
            } ?: return@launch

            val file = File(outputDir, "freeze_${clip.id}_$sourceMs.png")
            file.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 95, it)
            }

            snapshotForUndo()
            repository.insertFreezeFrame(
                projectId = projectId,
                clip = clip,
                playheadMs = playheadMs,
                imageUri = android.net.Uri.fromFile(file).toString(),
                durationMs = FREEZE_DURATION_MS,
            )
        }
    }

    /** Canvas aspect (9:16, 16:9, ...) — preview + export both follow. */
    fun setCanvas(width: Int, height: Int) {
        viewModelScope.launch {
            val p = project.value?.project ?: return@launch
            repository.updateProject(p.copy(canvasWidth = width, canvasHeight = height))
        }
    }

    fun seekTo(timelineMs: Long) = previewEngine.seekTo(timelineMs)

    fun togglePlayback(fromMs: Long) = previewEngine.togglePlayback(fromMs)

    fun export(outputDir: File, settings: ExportEngine.Settings) {
        val snapshot = project.value ?: return
        val layers = layersFrom(snapshot)
        if (layers.videoClips.isEmpty()) return
        // Free the decoders for the transformer on budget devices.
        previewEngine.pause()
        _exportLocation.value = null
        val outputFile = File(outputDir, "mastar_${snapshot.project.id}_export.mp4")
        exportEngine.export(
            layers = layers,
            outputFile = outputFile,
            canvasWidth = snapshot.project.canvasWidth,
            canvasHeight = snapshot.project.canvasHeight,
            settings = settings,
        ) { state ->
            _exportState.value = state
            if (state is ExportEngine.State.Done) {
                // Publish into the gallery so the user can actually find it.
                viewModelScope.launch(Dispatchers.IO) {
                    _exportLocation.value =
                        GalleryPublisher.publish(getApplication(), state.outputFile)
                }
            }
        }

        // Drive the progress percentage while the transformer runs.
        viewModelScope.launch {
            while (_exportState.value is ExportEngine.State.Exporting) {
                kotlinx.coroutines.delay(400)
                val current = _exportState.value
                if (current is ExportEngine.State.Exporting) {
                    _exportState.value =
                        ExportEngine.State.Exporting(exportEngine.queryProgress())
                }
            }
        }
    }

    /** Lottie stickers still render as Compose overlays (not in the GL graph yet). */
    fun stickersAt(timelineMs: Long): List<ClipEntity> =
        allClips().filter {
            it.type == ClipType.STICKER && !it.hidden &&
                timelineMs in it.timelineStartMs until it.timelineEndMs
        }

    private fun allClips(): List<ClipEntity> =
        project.value?.tracks?.flatMap { it.clips }.orEmpty()

    override fun onCleared() {
        previewEngine.release()
        exportEngine.cancel()
    }

    companion object {
        const val DEFAULT_OVERLAY_DURATION_MS = 3000L
        const val FREEZE_DURATION_MS = 3000L
        private const val MAX_UNDO = 50

        fun factory(application: Application, projectId: Long) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                    EditorViewModel(application, projectId) as T
            }
    }
}
