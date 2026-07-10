package com.mastar.editor.ui.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.mastar.editor.MastarApp
import com.mastar.editor.data.db.ClipType
import com.mastar.editor.data.db.ProjectWithTracks
import com.mastar.editor.data.db.TrackType
import com.mastar.editor.engine.export.ExportEngine
import com.mastar.editor.engine.playback.PreviewEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class EditorViewModel(
    application: Application,
    private val projectId: Long,
) : AndroidViewModel(application) {

    private val repository = (application as MastarApp).projectRepository

    val previewEngine = PreviewEngine(application)
    private val exportEngine = ExportEngine(application)

    val project: StateFlow<ProjectWithTracks?> =
        repository.observeProject(projectId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _selectedClipId = MutableStateFlow<Long?>(null)
    val selectedClipId: StateFlow<Long?> = _selectedClipId

    private val _exportState = MutableStateFlow<ExportEngine.State>(ExportEngine.State.Idle)
    val exportState: StateFlow<ExportEngine.State> = _exportState

    fun selectClip(clipId: Long?) {
        _selectedClipId.value = clipId
    }

    fun addVideoClip(sourceUri: String, sourceDurationMs: Long) {
        viewModelScope.launch {
            val snapshot = project.value ?: return@launch
            val videoTrack =
                snapshot.tracks.firstOrNull { it.track.type == TrackType.VIDEO } ?: return@launch
            val appendAtMs = videoTrack.clips.maxOfOrNull { it.timelineEndMs } ?: 0L
            repository.addClip(
                trackId = videoTrack.track.id,
                type = ClipType.VIDEO,
                sourceUri = sourceUri,
                sourceDurationMs = sourceDurationMs,
                timelineStartMs = appendAtMs,
            )
            refreshPreview()
        }
    }

    fun moveClip(clipId: Long, newTimelineStartMs: Long) {
        viewModelScope.launch {
            val clip = project.value?.tracks
                ?.flatMap { it.clips }
                ?.firstOrNull { it.id == clipId } ?: return@launch
            repository.updateClip(clip.copy(timelineStartMs = newTimelineStartMs))
            refreshPreview()
        }
    }

    fun splitSelectedClipAtPlayhead(playheadMs: Long) {
        viewModelScope.launch {
            val clipId = _selectedClipId.value ?: return@launch
            val clip = project.value?.tracks
                ?.flatMap { it.clips }
                ?.firstOrNull { it.id == clipId } ?: return@launch
            repository.splitClipAt(clip, playheadMs)
            refreshPreview()
        }
    }

    fun deleteSelectedClip() {
        viewModelScope.launch {
            val clipId = _selectedClipId.value ?: return@launch
            repository.deleteClip(clipId)
            _selectedClipId.value = null
            refreshPreview()
        }
    }

    fun seekTo(timelineMs: Long) {
        previewEngine.seekToTimeline(mainTrackClips(), timelineMs)
    }

    fun togglePlayback() {
        if (previewEngine.player.isPlaying) previewEngine.pause() else previewEngine.play()
    }

    fun export(outputDir: File) {
        val snapshot = project.value ?: return
        val clips = mainTrackClips()
        if (clips.isEmpty()) return
        val outputFile = File(outputDir, "mastar_${snapshot.project.id}_export.mp4")
        exportEngine.export(
            clips = clips,
            outputFile = outputFile,
            canvasWidth = snapshot.project.canvasWidth,
            canvasHeight = snapshot.project.canvasHeight,
        ) { state -> _exportState.value = state }
    }

    fun refreshPreview() {
        previewEngine.setTimeline(mainTrackClips())
    }

    private fun mainTrackClips() = project.value?.tracks
        ?.firstOrNull { it.track.type == TrackType.VIDEO }
        ?.clips
        ?.sortedBy { it.timelineStartMs }
        .orEmpty()

    override fun onCleared() {
        previewEngine.release()
        exportEngine.cancel()
    }

    companion object {
        fun factory(application: Application, projectId: Long) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                    EditorViewModel(application, projectId) as T
            }
    }
}
