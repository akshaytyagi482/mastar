package com.mastar.editor.engine.effects

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import com.mastar.editor.data.db.ClipEntity
import com.mastar.editor.data.db.KeyframeEntity
import com.mastar.editor.engine.keyframe.KeyframeEngine

/**
 * A styled, TRANSFORMABLE text overlay: visible only inside its clip's
 * timeline window, positioned/scaled/rotated by the clip's transform fields
 * and keyframes (evaluated per output frame). Applied at composition level,
 * so presentation time here is absolute project-timeline time.
 */
@UnstableApi
class TimedTextOverlay(
    payload: String,
    private val clip: ClipEntity,
    keyframes: List<KeyframeEntity>,
    /** Output-frame pixels per sp, so text keeps its proportion at any res. */
    pxPerSp: Float = 3f,
) : TextOverlay() {

    private val keyframesByProperty = keyframes.groupBy { it.property }

    private val visibleText: SpannableString = run {
        val style = TextPayload.fromPayload(payload)
        SpannableString(style.text).apply {
            fun span(what: Any) = setSpan(what, 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            span(ForegroundColorSpan(style.color.toInt()))
            span(AbsoluteSizeSpan((style.sizeSp * pxPerSp).toInt().coerceAtLeast(8)))
            if (style.bold) span(StyleSpan(Typeface.BOLD))
            if (style.background) span(BackgroundColorSpan(Color.argb(160, 0, 0, 0)))
            if (style.font != "sans") span(TypefaceSpan(androidFontFamily(style.font)))
        }
    }

    // A single space renders as an invisible 1-glyph bitmap; TextOverlay does
    // not support fully empty text, so this is the "hidden" state.
    private val hiddenText = SpannableString(" ")

    override fun getText(presentationTimeUs: Long): SpannableString {
        val timeMs = presentationTimeUs / 1000
        return if (timeMs in clip.timelineStartMs until clip.timelineEndMs) visibleText
        else hiddenText
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val timeMs = presentationTimeUs / 1000
        if (timeMs !in clip.timelineStartMs until clip.timelineEndMs) {
            return HIDDEN
        }
        val t = KeyframeEngine.transformAt(
            keyframesByProperty, timeMs - clip.timelineStartMs,
            clip.positionX, clip.positionY, clip.scale,
            clip.rotationDeg, clip.opacity, clip.volume,
        )
        val ndcX = t.positionX * 2f - 1f
        val ndcY = -(t.positionY * 2f - 1f)
        return StaticOverlaySettings.Builder()
            .setAlphaScale(t.opacity.coerceIn(0f, 1f))
            .setScale(t.scale, t.scale)
            .setRotationDegrees(-t.rotationDeg)
            .setBackgroundFrameAnchor(ndcX, ndcY)
            .setOverlayFrameAnchor(0f, 0f)
            .build()
    }

    private fun androidFontFamily(font: String): String = when (font) {
        "serif" -> "serif"
        "mono" -> "monospace"
        "cursive" -> "cursive"
        else -> "sans-serif"
    }

    private companion object {
        val HIDDEN: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()
    }
}
