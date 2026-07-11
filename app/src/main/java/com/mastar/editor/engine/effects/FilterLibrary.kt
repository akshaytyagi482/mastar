package com.mastar.editor.engine.effects

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment

/**
 * Professional color filters as pure GPU effects (Media3 GlEffects) with a
 * CapCut-style 0..1 intensity slider. The same effect list runs in preview
 * and export, so what you see is what you ship. All recipes are original —
 * no extracted assets — and run fully offline.
 */
@UnstableApi
object FilterLibrary {

    data class FilterInfo(
        val id: String,
        val displayName: String,
        val category: String,
        /** Hinglish search tags mapped to Indian internet culture. */
        val tags: List<String>,
    )

    /**
     * A filter recipe in neutral units, scaled by intensity at build time:
     * rgb scales lerp from 1.0, additive params lerp from 0.
     */
    private data class Recipe(
        val red: Float = 1f,
        val green: Float = 1f,
        val blue: Float = 1f,
        val saturation: Float = 0f,
        val hue: Float = 0f,
        val contrast: Float = 0f,
        val brightness: Float = 0f,
        val grayscale: Boolean = false,
    )

    val FILTERS = listOf(
        FilterInfo("noir", "Desi Noir", "B&W", listOf("bw", "black", "white", "noir", "shayari")),
        FilterInfo("goldenhour", "Golden Hour", "Warm", listOf("warm", "sunset", "haldi", "wedding")),
        FilterInfo("mumbaiblue", "Mumbai Blue", "Cool", listOf("cool", "blue", "monsoon", "sad")),
        FilterInfo("desiswag", "Desi Swag", "Vivid", listOf("vivid", "pop", "swag", "punchy")),
        FilterInfo("filmy90s", "90s Filmy", "Retro", listOf("retro", "film", "purana", "filmy")),
        FilterInfo("sadvibe", "Sad Vibe", "Mood", listOf("fade", "sad", "mood", "shayari")),
        FilterInfo("cinema", "Cinema Teal", "Cinema", listOf("cinema", "teal", "orange", "movie")),
        FilterInfo("vintage", "Vintage", "Retro", listOf("vintage", "old", "classic")),
        FilterInfo("foodie", "Foodie", "Food", listOf("food", "khana", "tasty", "zomato")),
        FilterInfo("hariyali", "Hariyali", "Nature", listOf("nature", "green", "hariyali", "travel")),
        FilterInfo("portrait", "Portrait Glow", "Portrait", listOf("portrait", "face", "glow", "selfie")),
        FilterInfo("midnight", "Midnight", "Mood", listOf("dark", "night", "raat", "moody")),
    )

    private val RECIPES = mapOf(
        "noir" to Recipe(grayscale = true, contrast = 0.25f),
        "goldenhour" to Recipe(red = 1.12f, green = 1.03f, blue = 0.88f, brightness = 0.05f),
        "mumbaiblue" to Recipe(red = 0.9f, green = 0.98f, blue = 1.15f, contrast = 0.1f),
        "desiswag" to Recipe(saturation = 35f, contrast = 0.18f),
        "filmy90s" to Recipe(red = 1.08f, blue = 0.85f, saturation = -18f, brightness = 0.04f),
        "sadvibe" to Recipe(saturation = -30f, contrast = -0.12f, brightness = 0.06f),
        "cinema" to Recipe(red = 1.06f, green = 1.0f, blue = 1.08f, saturation = -12f, contrast = 0.2f),
        "vintage" to Recipe(red = 1.05f, green = 1.0f, blue = 0.82f, saturation = -25f, contrast = -0.08f, brightness = 0.05f),
        "foodie" to Recipe(red = 1.1f, green = 1.04f, blue = 0.92f, saturation = 22f, contrast = 0.12f),
        "hariyali" to Recipe(red = 0.95f, green = 1.1f, blue = 0.95f, saturation = 18f),
        "portrait" to Recipe(red = 1.06f, green = 1.02f, blue = 0.98f, saturation = 8f, contrast = -0.05f, brightness = 0.06f),
        "midnight" to Recipe(red = 0.92f, green = 0.95f, blue = 1.08f, saturation = -15f, contrast = 0.22f, brightness = -0.06f),
    )

    fun search(query: String): List<FilterInfo> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return FILTERS
        return FILTERS.filter { f ->
            f.id.contains(q) || f.displayName.lowercase().contains(q) ||
                f.category.lowercase().contains(q) || f.tags.any { it.contains(q) }
        }
    }

    /**
     * Resolves a filter id to its GPU chain at the given intensity (0..1).
     * Unknown ids resolve to an empty list so old projects never crash.
     */
    fun effectsFor(filterId: String?, intensity: Float = 1f): List<Effect> {
        val recipe = RECIPES[filterId] ?: return emptyList()
        val i = intensity.coerceIn(0f, 1f)
        if (i == 0f) return emptyList()

        return buildList {
            if (recipe.grayscale) {
                // Intensity for B&W = desaturation amount.
                add(HslAdjustment.Builder().adjustSaturation(-100f * i).build())
            }
            if (recipe.red != 1f || recipe.green != 1f || recipe.blue != 1f) {
                add(
                    RgbAdjustment.Builder()
                        .setRedScale(lerp(1f, recipe.red, i))
                        .setGreenScale(lerp(1f, recipe.green, i))
                        .setBlueScale(lerp(1f, recipe.blue, i))
                        .build()
                )
            }
            if (recipe.saturation != 0f || recipe.hue != 0f) {
                add(
                    HslAdjustment.Builder()
                        .adjustSaturation(recipe.saturation * i)
                        .adjustHue(recipe.hue * i)
                        .build()
                )
            }
            if (recipe.contrast != 0f) add(Contrast(recipe.contrast * i))
            if (recipe.brightness != 0f) add(Brightness(recipe.brightness * i))
        }
    }

    private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t
}
