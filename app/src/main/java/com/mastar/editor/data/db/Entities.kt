package com.mastar.editor.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Non-destructive editing: source media files are NEVER modified.
 * A "cut" is just a pair of timestamps stored here; Media3 clips playback
 * to that window at render time. This is what keeps editing lag-free.
 */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMs: Long,
    val modifiedAtMs: Long,
    /** Export canvas, e.g. 1080x1920 for Reels/Shorts. */
    val canvasWidth: Int = 1080,
    val canvasHeight: Int = 1920,
    val frameRate: Int = 30,
)

enum class TrackType { VIDEO, AUDIO, OVERLAY, TEXT, STICKER, FILTER }

@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("projectId")],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val type: TrackType,
    /** Z-order: 0 = main video track, higher = rendered on top (PIP layers). */
    val zOrder: Int,
)

enum class ClipType { VIDEO, AUDIO, IMAGE, TEXT, STICKER, FILTER }

@Entity(
    tableName = "clips",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("trackId")],
)
data class ClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val type: ClipType,
    /** content:// or file:// URI of the untouched source media. */
    val sourceUri: String,
    /** Window into the source file (the "cut", in source-media time). */
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    /** Full length of the source file — the ceiling when re-expanding a trim. */
    val sourceDurationMs: Long = 0,
    /** Native pixel size of the source media (0 = unknown, probed at import). */
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    /** Where the clip sits on the project timeline. */
    val timelineStartMs: Long,
    /** 1.0 = normal. Raw speed is handled by Media3; smooth curves come later. */
    val speed: Float = 1f,
    val volume: Float = 1f,
    /** Audio fade ramps at the clip's edges. */
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    /** Voice effect preset id (see VoiceEffects). */
    val voiceEffectId: String? = null,
    /** Static transform (keyframes override these when present). */
    val positionX: Float = 0.5f,
    val positionY: Float = 0.5f,
    val scale: Float = 1f,
    val rotationDeg: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val opacity: Float = 1f,
    /** Manual color adjustments, all normalized to -1..1 (0 = untouched). */
    val adjustBrightness: Float = 0f,
    val adjustContrast: Float = 0f,
    val adjustSaturation: Float = 0f,
    val adjustHue: Float = 0f,
    val adjustTemperature: Float = 0f,
    val adjustTint: Float = 0f,
    /** GL transition INTO the next clip, e.g. "fade", "directionalwipe". */
    val transitionId: String? = null,
    val transitionDurationMs: Long = 0,
    /** Single-clip entrance/exit animation (CapCut "Animation → In/Out"). */
    val animInId: String? = null,
    val animInDurationMs: Long = 0,
    val animOutId: String? = null,
    val animOutDurationMs: Long = 0,
    /** LUT filter asset name (PNG strip in assets/luts) + 0..1 intensity. */
    val filterId: String? = null,
    val filterIntensity: Float = 1f,
    /** For TEXT clips: the rendered text. For STICKER clips: Lottie asset name. */
    val payload: String? = null,
    /** User-given clip name (long-press → Rename). */
    val displayName: String? = null,
    /** Mute audio without losing the volume setting. */
    val muted: Boolean = false,
    /** Locked clips can't be moved, trimmed, or deleted. */
    val locked: Boolean = false,
    /** Hidden clips stay on the timeline but don't render (eye toggle). */
    val hidden: Boolean = false,
    /** Clips sharing a groupId move together. */
    val groupId: Long? = null,
    /** Speed ramp curve (SpeedCurve JSON); overrides [speed] when set. */
    val speedCurveJson: String? = null,
) {
    /** Duration on the timeline, accounting for speed (curve-aware). */
    val timelineDurationMs: Long
        get() {
            val sourceSpanMs = sourceEndMs - sourceStartMs
            val curve = com.mastar.editor.engine.speed.SpeedCurve.parse(speedCurveJson)
            return if (curve != null) curve.outputDurationMs(sourceSpanMs)
            else (sourceSpanMs / speed).toLong()
        }

    val timelineEndMs: Long
        get() = timelineStartMs + timelineDurationMs
}

/** Animatable properties for the keyframe system. */
enum class KeyframeProperty { POSITION_X, POSITION_Y, SCALE, ROTATION, OPACITY, VOLUME }

enum class EasingType {
    LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT,
    // CapCut-style graph strengths.
    EASE_IN_2, EASE_IN_3, EASE_OUT_2, EASE_OUT_3,
}

@Entity(
    tableName = "keyframes",
    foreignKeys = [
        ForeignKey(
            entity = ClipEntity::class,
            parentColumns = ["id"],
            childColumns = ["clipId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("clipId")],
)
data class KeyframeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clipId: Long,
    val property: KeyframeProperty,
    /** Time relative to the clip's timeline start. */
    val timeMs: Long,
    val value: Float,
    /** Easing applied on the segment leaving this keyframe. */
    val easing: EasingType = EasingType.LINEAR,
)
