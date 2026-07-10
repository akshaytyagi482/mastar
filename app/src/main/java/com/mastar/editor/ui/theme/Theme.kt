package com.mastar.editor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Mastar brand palette: saffron accent on deep charcoal.
val Saffron = Color(0xFFFF9933)
val SaffronDim = Color(0xFFB36A20)
val Charcoal = Color(0xFF121212)
val CharcoalSurface = Color(0xFF1E1E1E)
val TrackLaneColor = Color(0xFF262626)
val ClipVideo = Color(0xFF3D5AFE)
val ClipAudio = Color(0xFF00C853)
val ClipOverlay = Color(0xFFAA00FF)
val ClipText = Color(0xFFFFD600)
val PlayheadRed = Color(0xFFFF1744)
val KeyframeDiamond = Color(0xFFFFFFFF)

private val MastarDarkColorScheme = darkColorScheme(
    primary = Saffron,
    onPrimary = Color.Black,
    secondary = ClipVideo,
    background = Charcoal,
    surface = CharcoalSurface,
    onBackground = Color.White,
    onSurface = Color.White,
)

/**
 * The editor is intentionally dark-only: video previews read best against
 * black, and dark UI saves battery on AMOLED panels.
 */
@Composable
fun MastarTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = MastarDarkColorScheme,
        content = content,
    )
}
