package com.mastar.editor.data.repo

import com.mastar.editor.data.db.ClipEntity
import com.mastar.editor.data.db.ClipType
import com.mastar.editor.data.db.KeyframeEntity
import com.mastar.editor.data.db.MastarDatabase
import com.mastar.editor.data.db.ProjectEntity
import com.mastar.editor.data.db.ProjectWithTracks
import com.mastar.editor.data.db.TrackEntity
import com.mastar.editor.data.db.TrackType
import kotlinx.coroutines.flow.Flow

/**
 * Single write-path for timeline edits. Every edit is a small DB row change,
 * so undo/redo can later be implemented as an operation log on top of this.
 */
class ProjectRepository(private val db: MastarDatabase) {

    fun observeProjects(): Flow<List<ProjectEntity>> = db.projectDao().observeProjects()

    fun observeProject(projectId: Long): Flow<ProjectWithTracks?> =
        db.projectDao().observeProjectWithTracks(projectId)

    /** Creates a project pre-seeded with a main video track and a music track. */
    suspend fun createProject(name: String, nowMs: Long): Long {
        val projectId = db.projectDao().insertProject(
            ProjectEntity(name = name, createdAtMs = nowMs, modifiedAtMs = nowMs)
        )
        db.trackDao().insertTrack(TrackEntity(projectId = projectId, type = TrackType.VIDEO, zOrder = 0))
        db.trackDao().insertTrack(TrackEntity(projectId = projectId, type = TrackType.AUDIO, zOrder = 1))
        return projectId
    }

    suspend fun addClip(
        trackId: Long,
        type: ClipType,
        sourceUri: String,
        sourceDurationMs: Long,
        timelineStartMs: Long,
    ): Long = db.clipDao().insertClip(
        ClipEntity(
            trackId = trackId,
            type = type,
            sourceUri = sourceUri,
            sourceStartMs = 0,
            sourceEndMs = sourceDurationMs,
            timelineStartMs = timelineStartMs,
        )
    )

    suspend fun updateClip(clip: ClipEntity) = db.clipDao().updateClip(clip)

    suspend fun deleteClip(clipId: Long) = db.clipDao().deleteClip(clipId)

    /** Split at a project-timeline position; converts to source time internally. */
    suspend fun splitClipAt(clip: ClipEntity, timelineMs: Long) {
        val offsetIntoClip = timelineMs - clip.timelineStartMs
        if (offsetIntoClip <= 0 || timelineMs >= clip.timelineEndMs) return
        val atSourceMs = clip.sourceStartMs + (offsetIntoClip * clip.speed).toLong()
        db.clipDao().splitClip(clip.id, atSourceMs, timelineMs)
    }

    /** Returns the project's track of [type], creating it (top z-order) if absent. */
    suspend fun ensureTrack(projectId: Long, type: TrackType): Long {
        val tracks = db.trackDao().tracksForProject(projectId)
        tracks.firstOrNull { it.type == type }?.let { return it.id }
        val topZ = (tracks.maxOfOrNull { it.zOrder } ?: 0) + 1
        return db.trackDao().insertTrack(
            TrackEntity(projectId = projectId, type = type, zOrder = topZ)
        )
    }

    /** Overlay clips (TEXT/STICKER) have no source media; duration is explicit. */
    suspend fun addOverlayClip(
        trackId: Long,
        type: ClipType,
        payload: String,
        timelineStartMs: Long,
        durationMs: Long,
    ): Long = db.clipDao().insertClip(
        ClipEntity(
            trackId = trackId,
            type = type,
            sourceUri = "",
            sourceStartMs = 0,
            sourceEndMs = durationMs,
            timelineStartMs = timelineStartMs,
            payload = payload,
        )
    )

    suspend fun addKeyframe(keyframe: KeyframeEntity): Long =
        db.keyframeDao().insertKeyframe(keyframe)

    suspend fun keyframesForClip(clipId: Long): List<KeyframeEntity> =
        db.keyframeDao().keyframesForClip(clipId)

    suspend fun touchProject(project: ProjectEntity, nowMs: Long) =
        db.projectDao().updateProject(project.copy(modifiedAtMs = nowMs))
}
