package com.mastar.editor.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class TrackWithClips(
    @Embedded val track: TrackEntity,
    @Relation(parentColumn = "id", entityColumn = "trackId")
    val clips: List<ClipEntity>,
)

data class ProjectWithTracks(
    @Embedded val project: ProjectEntity,
    @Relation(entity = TrackEntity::class, parentColumn = "id", entityColumn = "projectId")
    val tracks: List<TrackWithClips>,
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY modifiedAtMs DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Transaction
    @Query("SELECT * FROM projects WHERE id = :projectId")
    fun observeProjectWithTracks(projectId: Long): Flow<ProjectWithTracks?>

    @Insert
    suspend fun insertProject(project: ProjectEntity): Long

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: Long)
}

@Dao
interface TrackDao {
    @Insert
    suspend fun insertTrack(track: TrackEntity): Long

    @Query("SELECT * FROM tracks WHERE projectId = :projectId ORDER BY zOrder")
    suspend fun tracksForProject(projectId: Long): List<TrackEntity>
}

@Dao
interface ClipDao {
    @Insert
    suspend fun insertClip(clip: ClipEntity): Long

    @Update
    suspend fun updateClip(clip: ClipEntity)

    @Query("DELETE FROM clips WHERE id = :clipId")
    suspend fun deleteClip(clipId: Long)

    @Query("SELECT * FROM clips WHERE id = :clipId")
    suspend fun clipById(clipId: Long): ClipEntity?

    /**
     * Splitting a clip = replacing one timestamp row with two. The source
     * file is untouched — this is why splits are instant even for 4K media.
     * [atSourceMs] is in source-media time, [atTimelineMs] in project time.
     */
    @Transaction
    suspend fun splitClip(clipId: Long, atSourceMs: Long, atTimelineMs: Long) {
        val original = clipById(clipId) ?: return
        if (atSourceMs <= original.sourceStartMs || atSourceMs >= original.sourceEndMs) return
        updateClip(original.copy(sourceEndMs = atSourceMs))
        insertClip(
            original.copy(
                id = 0,
                sourceStartMs = atSourceMs,
                timelineStartMs = atTimelineMs,
                // The transition into the next clip belongs to the second half.
                transitionId = original.transitionId,
            )
        )
    }
}

@Dao
interface KeyframeDao {
    @Insert
    suspend fun insertKeyframe(keyframe: KeyframeEntity): Long

    @Query("SELECT * FROM keyframes WHERE clipId = :clipId ORDER BY timeMs")
    suspend fun keyframesForClip(clipId: Long): List<KeyframeEntity>

    @Query("SELECT * FROM keyframes WHERE clipId = :clipId ORDER BY timeMs")
    fun observeKeyframesForClip(clipId: Long): Flow<List<KeyframeEntity>>

    @Query("DELETE FROM keyframes WHERE id = :keyframeId")
    suspend fun deleteKeyframe(keyframeId: Long)
}
