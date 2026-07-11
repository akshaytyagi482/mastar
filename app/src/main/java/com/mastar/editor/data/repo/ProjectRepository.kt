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

    fun observeProjectsWithTracks(): Flow<List<ProjectWithTracks>> =
        db.projectDao().observeProjectsWithTracks()

    suspend fun updateProject(project: ProjectEntity) = db.projectDao().updateProject(project)

    suspend fun deleteProject(projectId: Long) = db.projectDao().deleteProject(projectId)

    /** Restores a clip snapshot (undo/redo). */
    suspend fun replaceAllClips(projectId: Long, clips: List<ClipEntity>) =
        db.clipDao().replaceAllClips(projectId, clips)

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
        sourceWidth: Int = 0,
        sourceHeight: Int = 0,
    ): Long = db.clipDao().insertClip(
        ClipEntity(
            trackId = trackId,
            type = type,
            sourceUri = sourceUri,
            sourceStartMs = 0,
            sourceEndMs = sourceDurationMs,
            sourceDurationMs = sourceDurationMs,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            timelineStartMs = timelineStartMs,
        )
    )

    /**
     * Multi-lane overlays: returns an OVERLAY track whose time range is free
     * for [startMs, startMs+durationMs), creating a new lane when every
     * existing one is occupied — video over video over video, no manual
     * lane management.
     */
    suspend fun findOrCreatePipLane(projectId: Long, startMs: Long, durationMs: Long): Long {
        val fresh = db.projectDao().projectWithTracks(projectId)
        val endMs = startMs + durationMs
        fresh?.tracks
            ?.filter { it.track.type == TrackType.OVERLAY }
            ?.sortedBy { it.track.zOrder }
            ?.forEach { lane ->
                val occupied = lane.clips.any {
                    it.timelineStartMs < endMs && it.timelineEndMs > startMs
                }
                if (!occupied) return lane.track.id
            }
        val topZ = (fresh?.tracks?.maxOfOrNull { it.track.zOrder } ?: 0) + 1
        return db.trackDao().insertTrack(
            TrackEntity(projectId = projectId, type = TrackType.OVERLAY, zOrder = topZ)
        )
    }

    suspend fun updateClip(clip: ClipEntity) = db.clipDao().updateClip(clip)

    suspend fun deleteClip(clipId: Long) = db.clipDao().deleteClip(clipId)

    /**
     * Freeze frame: splits [clip] at [playheadMs], ripples same-track clips
     * right by [durationMs], and inserts the still image in the gap.
     * Reads fresh DB state between steps (the observing Flow lags writes).
     */
    suspend fun insertFreezeFrame(
        projectId: Long,
        clip: ClipEntity,
        playheadMs: Long,
        imageUri: String,
        durationMs: Long,
    ) {
        splitClipAt(clip, playheadMs)
        val fresh = db.projectDao().projectWithTracks(projectId) ?: return
        fresh.tracks.firstOrNull { it.track.id == clip.trackId }?.clips
            ?.filter { it.timelineStartMs >= playheadMs }
            ?.forEach { c ->
                db.clipDao().updateClip(c.copy(timelineStartMs = c.timelineStartMs + durationMs))
            }
        db.clipDao().insertClip(
            ClipEntity(
                trackId = clip.trackId,
                type = ClipType.IMAGE,
                sourceUri = imageUri,
                sourceStartMs = 0,
                sourceEndMs = durationMs,
                sourceDurationMs = durationMs,
                timelineStartMs = playheadMs,
            )
        )
    }

    /** Split at a project-timeline position; converts to source time internally. */
    suspend fun splitClipAt(clip: ClipEntity, timelineMs: Long) {
        val offsetIntoClip = timelineMs - clip.timelineStartMs
        if (offsetIntoClip <= 0 || timelineMs >= clip.timelineEndMs) return
        val atSourceMs = clip.sourceStartMs + (offsetIntoClip * clip.speed).toLong()
        db.clipDao().splitClip(clip.id, atSourceMs, timelineMs)
    }

    /** CapCut-style duplicate: the copy lands right after the original. */
    suspend fun duplicateClip(clip: ClipEntity): Long =
        db.clipDao().insertClip(
            clip.copy(id = 0, timelineStartMs = clip.timelineEndMs, groupId = null)
        )

    /** Insert mode: pushes overlapping clips right on [trackId]. */
    suspend fun resolveTrackOverlaps(projectId: Long, trackId: Long) {
        val fresh = db.projectDao().projectWithTracks(projectId) ?: return
        val trackClips = fresh.tracks.firstOrNull { it.track.id == trackId }?.clips ?: return
        com.mastar.editor.engine.timeline.TimelineOps.resolveOverlaps(trackClips)
            .forEach { db.clipDao().updateClip(it) }
    }

    /** Ripple delete: removes the clip and closes the hole behind it. */
    suspend fun rippleDelete(projectId: Long, clip: ClipEntity) {
        val fresh = db.projectDao().projectWithTracks(projectId) ?: return
        val trackClips = fresh.tracks.firstOrNull { it.track.id == clip.trackId }?.clips ?: return
        db.clipDao().deleteClip(clip.id)
        com.mastar.editor.engine.timeline.TimelineOps.rippleShiftAfterDelete(trackClips, clip)
            .forEach { db.clipDao().updateClip(it) }
    }

    /** Moves every clip sharing [groupId] by [deltaMs] (grouped drag). */
    suspend fun moveGroup(projectId: Long, groupId: Long, deltaMs: Long) {
        val fresh = db.projectDao().projectWithTracks(projectId) ?: return
        fresh.tracks.flatMap { it.clips }
            .filter { it.groupId == groupId && !it.locked }
            .forEach { c ->
                db.clipDao().updateClip(
                    c.copy(timelineStartMs = (c.timelineStartMs + deltaMs).coerceAtLeast(0))
                )
            }
    }

    /** Assigns (or clears, with null) a shared group id. */
    suspend fun setGroup(clipIds: Collection<Long>, groupId: Long?) {
        for (id in clipIds) {
            db.clipDao().clipById(id)?.let { db.clipDao().updateClip(it.copy(groupId = groupId)) }
        }
    }

    /** Deep-copies a project with all tracks and clips. */
    suspend fun duplicateProject(projectId: Long, nowMs: Long): Long? {
        val source = db.projectDao().projectWithTracks(projectId) ?: return null
        val newProjectId = db.projectDao().insertProject(
            source.project.copy(
                id = 0,
                name = "${source.project.name} copy",
                createdAtMs = nowMs,
                modifiedAtMs = nowMs,
            )
        )
        for (trackWithClips in source.tracks) {
            val newTrackId = db.trackDao().insertTrack(
                trackWithClips.track.copy(id = 0, projectId = newProjectId)
            )
            for (clip in trackWithClips.clips) {
                db.clipDao().insertClip(clip.copy(id = 0, trackId = newTrackId))
            }
        }
        return newProjectId
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

    /** PIP layer clip: starts centered at half size so it reads as a layer. */
    suspend fun addPipClip(
        trackId: Long,
        type: ClipType,
        sourceUri: String,
        sourceDurationMs: Long,
        timelineStartMs: Long,
        sourceWidth: Int = 0,
        sourceHeight: Int = 0,
    ): Long = db.clipDao().insertClip(
        ClipEntity(
            trackId = trackId,
            type = type,
            sourceUri = sourceUri,
            sourceStartMs = 0,
            sourceEndMs = sourceDurationMs,
            sourceDurationMs = sourceDurationMs,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            timelineStartMs = timelineStartMs,
            scale = 0.5f,
        )
    )

    /** Filter LAYER clip: grades everything under it for its time range. */
    suspend fun addFilterLayerClip(
        trackId: Long,
        filterId: String,
        timelineStartMs: Long,
        durationMs: Long,
    ): Long = db.clipDao().insertClip(
        ClipEntity(
            trackId = trackId,
            type = ClipType.FILTER,
            sourceUri = "",
            sourceStartMs = 0,
            sourceEndMs = durationMs,
            sourceDurationMs = 3_600_000,
            timelineStartMs = timelineStartMs,
            filterId = filterId,
        )
    )

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
            // Overlays have no real source; allow trimming out to an hour.
            sourceDurationMs = 3_600_000,
            timelineStartMs = timelineStartMs,
            payload = payload,
        )
    )

    suspend fun addKeyframe(keyframe: KeyframeEntity): Long =
        db.keyframeDao().insertKeyframe(keyframe)

    suspend fun deleteKeyframe(keyframeId: Long) =
        db.keyframeDao().deleteKeyframe(keyframeId)

    suspend fun updateKeyframe(keyframe: KeyframeEntity) =
        db.keyframeDao().updateKeyframe(keyframe)

    fun observeKeyframesForProject(projectId: Long): Flow<List<KeyframeEntity>> =
        db.keyframeDao().observeKeyframesForProject(projectId)

    suspend fun keyframesForClip(clipId: Long): List<KeyframeEntity> =
        db.keyframeDao().keyframesForClip(clipId)

    suspend fun touchProject(project: ProjectEntity, nowMs: Long) =
        db.projectDao().updateProject(project.copy(modifiedAtMs = nowMs))
}
