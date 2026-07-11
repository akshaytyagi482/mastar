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
import com.mastar.editor.data.db.ProjectWithTracks
import com.mastar.editor.data.db.TrackType
import com.mastar.editor.engine.CompositionFactory
import com.mastar.editor.engine.export.ExportEngine
import com.mastar.editor.engine.playback.PreviewEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

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

    private val _selectedClipId = MutableStateFlow<Long?>(null)
    val selectedClipId: StateFlow<Long?> = _selectedClipId

    private val _exportState = MutableStateFlow<ExportEngine.State>(ExportEngine.State.Idle)
    val exportState: StateFlow<ExportEngine.State> = _exportState

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
        // The DB is the single source of truth: any clip change (from any
        // code path) lands here and rebuilds the preview composition once.
        viewModelScope.launch {
            project.collect { p ->
                p ?: return@collect
                val canvas = p.project.canvasWidth to p.project.canvasHeight
                val layers = layersFrom(p)
                if (layers != lastLayers || canvas != lastCanvas) {
                    lastLayers = layers
                    lastCanvas = canvas
                    previewEngine.setCanvas(canvas.first, canvas.second)
                    previewEngine.setTimeline(layers)
                }
            }
        }
    }

    private fun layersFrom(p: ProjectWithTracks): CompositionFactory.Layers {
        fun clipsOf(type: TrackType) = p.tracks
            .filter { it.track.type == type }
            .flatMap { it.clips }
            .sortedBy { it.timelineStartMs }
        return CompositionFactory.Layers(
            videoClips = clipsOf(TrackType.VIDEO),
            overlayClips = clipsOf(TrackType.OVERLAY),
            audioClips = clipsOf(TrackType.AUDIO),
            textClips = clipsOf(TrackType.TEXT),
        )
    }

    val projectDurationMs: Long
        get() = allClips().maxOfOrNull { it.timelineEndMs } ?: 0L

    fun selectedClip(): ClipEntity? =
        _selectedClipId.value?.let { id -> allClips().firstOrNull { it.id == id } }

    fun isOverlayClip(clipId: Long): Boolean =
        project.value?.tracks
            ?.firstOrNull { it.track.type == TrackType.OVERLAY }
            ?.clips?.any { it.id == clipId } == true

    fun selectClip(clipId: Long?) {
        _selectedClipId.value = clipId
    }

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

    fun addVideoClip(sourceUri: String, sourceDurationMs: Long) {
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
            )
        }
    }

    /** PIP layer: video or photo floating over the main track. */
    fun addPipClip(sourceUri: String, isImage: Boolean, sourceDurationMs: Long, atMs: Long) {
        viewModelScope.launch {
            snapshotForUndo()
            val trackId = repository.ensureTrack(projectId, TrackType.OVERLAY)
            repository.addPipClip(
                trackId = trackId,
                type = if (isImage) ClipType.IMAGE else ClipType.VIDEO,
                sourceUri = sourceUri,
                sourceDurationMs = sourceDurationMs,
                timelineStartMs = atMs,
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
            snapshotForUndo()
            repository.updateClip(transform(clip))
        }
    }

    fun moveClip(clipId: Long, newTimelineStartMs: Long) {
        updateClip(clipId) { it.copy(timelineStartMs = newTimelineStartMs) }
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
            snapshotForUndo()
            repository.splitClipAt(clip, playheadMs)
        }
    }

    fun deleteSelectedClip() {
        viewModelScope.launch {
            val clipId = _selectedClipId.value ?: return@launch
            snapshotForUndo()
            repository.deleteClip(clipId)
            _selectedClipId.value = null
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

    fun togglePlayback() = previewEngine.togglePlayback()

    fun export(outputDir: File, settings: ExportEngine.Settings) {
        val snapshot = project.value ?: return
        val layers = layersFrom(snapshot)
        if (layers.videoClips.isEmpty()) return
        // Free the decoders for the transformer on budget devices.
        previewEngine.pause()
        val outputFile = File(outputDir, "mastar_${snapshot.project.id}_export.mp4")
        exportEngine.export(
            layers = layers,
            outputFile = outputFile,
            canvasWidth = snapshot.project.canvasWidth,
            canvasHeight = snapshot.project.canvasHeight,
            settings = settings,
        ) { state -> _exportState.value = state }

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
            it.type == ClipType.STICKER &&
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
