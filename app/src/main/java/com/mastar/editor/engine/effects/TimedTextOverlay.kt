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
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.TextOverlay

/**
 * A styled text overlay that is only visible inside its clip's timeline
 * window. Applied at composition level during export, so presentation time
 * here is absolute project-timeline time — exactly what ClipEntity stores.
 */
@UnstableApi
class TimedTextOverlay(
    payload: String,
    private val startMs: Long,
    private val endMs: Long,
    /** Output-frame pixels per sp, so text keeps its proportion at any res. */
    pxPerSp: Float = 3f,
) : TextOverlay() {

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
        return if (timeMs in startMs until endMs) visibleText else hiddenText
    }

    private fun androidFontFamily(font: String): String = when (font) {
        "serif" -> "serif"
        "mono" -> "monospace"
        "cursive" -> "cursive"
        else -> "sans-serif"
    }
}
