package com.mastar.editor.engine.effects

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter

/**
 * Professional color filters as pure GPU effects (Media3 GlEffects).
 * The exact same effect list runs in the preview (ExoPlayer.setVideoEffects)
 * and in the export (Transformer), so what you see is what you ship.
 *
 * Every filter is an original color recipe — no extracted assets — and all
 * of them run on-device with zero network.
 */
@UnstableApi
object FilterLibrary {

    data class FilterInfo(
        val id: String,
        val displayName: String,
        /** Hinglish search tags mapped to Indian internet culture. */
        val tags: List<String>,
    )

    val FILTERS = listOf(
        FilterInfo("noir", "Desi Noir", listOf("bw", "black", "white", "noir", "classic", "shayari")),
        FilterInfo("goldenhour", "Golden Hour", listOf("warm", "sunset", "golden", "haldi", "wedding")),
        FilterInfo("mumbaiblue", "Mumbai Blue", listOf("cool", "blue", "rain", "monsoon", "sad", "vibe")),
        FilterInfo("desiswag", "Desi Swag", listOf("vivid", "pop", "swag", "punchy", "color")),
        FilterInfo("filmy90s", "90s Filmy", listOf("retro", "film", "vintage", "purana", "filmy")),
        FilterInfo("sadvibe", "Sad Vibe", listOf("fade", "sad", "mood", "soft", "shayari")),
    )

    fun search(query: String): List<FilterInfo> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return FILTERS
        return FILTERS.filter { f ->
            f.id.contains(q) || f.displayName.lowercase().contains(q) || f.tags.any { it.contains(q) }
        }
    }

    /**
     * Resolves a filter id to its GPU effect chain. Unknown ids resolve to
     * an empty list (no-op) so old projects never crash on missing filters.
     */
    fun effectsFor(filterId: String?): List<Effect> = when (filterId) {
        "noir" -> listOf(
            RgbFilter.createGrayscaleFilter(),
            Contrast(0.25f),
        )
        "goldenhour" -> listOf(
            RgbAdjustment.Builder()
                .setRedScale(1.12f)
                .setGreenScale(1.03f)
                .setBlueScale(0.88f)
                .build(),
            Brightness(0.05f),
        )
        "mumbaiblue" -> listOf(
            RgbAdjustment.Builder()
                .setRedScale(0.9f)
                .setGreenScale(0.98f)
                .setBlueScale(1.15f)
                .build(),
            Contrast(0.1f),
        )
        "desiswag" -> listOf(
            HslAdjustment.Builder().adjustSaturation(35f).build(),
            Contrast(0.18f),
        )
        "filmy90s" -> listOf(
            RgbAdjustment.Builder()
                .setRedScale(1.08f)
                .setGreenScale(1.0f)
                .setBlueScale(0.85f)
                .build(),
            HslAdjustment.Builder().adjustSaturation(-18f).build(),
            Brightness(0.04f),
        )
        "sadvibe" -> listOf(
            HslAdjustment.Builder().adjustSaturation(-30f).build(),
            Contrast(-0.12f),
            Brightness(0.06f),
        )
        else -> emptyList()
    }
}
