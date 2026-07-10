package com.mastar.editor.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.mastar.editor.R
import com.mastar.editor.data.db.ClipEntity
import com.mastar.editor.data.db.ClipType
import com.mastar.editor.engine.effects.FilterLibrary

/**
 * Bottom panel shown when a clip is selected: speed curve entry point,
 * volume, color filter, and the transition into the next clip.
 * Slider edits are committed on release so Room writes stay off the
 * gesture hot path.
 */
@UnstableApi
@Composable
fun ClipInspector(
    clip: ClipEntity,
    onUpdate: (transform: (ClipEntity) -> ClipEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (clip.type == ClipType.VIDEO || clip.type == ClipType.AUDIO) {
            // Speed: 0.25x..4x, committed on release.
            var speed by remember(clip.id) { mutableFloatStateOf(clip.speed) }
            LabeledSlider(
                label = "${stringResource(R.string.speed)} ${"%.2f".format(speed)}x",
                value = speed,
                range = 0.25f..4f,
                onValue = { speed = it },
                onCommit = { onUpdate { c -> c.copy(speed = speed) } },
            )

            var volume by remember(clip.id) { mutableFloatStateOf(clip.volume) }
            LabeledSlider(
                label = "${stringResource(R.string.volume)} ${(volume * 100).toInt()}%",
                value = volume,
                range = 0f..2f,
                onValue = { volume = it },
                onCommit = { onUpdate { c -> c.copy(volume = volume) } },
            )
        }

        if (clip.type == ClipType.VIDEO || clip.type == ClipType.IMAGE) {
            ChipRow(
                title = stringResource(R.string.filter),
                options = FilterLibrary.FILTERS.map { it.id to it.displayName },
                selectedId = clip.filterId,
                onSelect = { id -> onUpdate { c -> c.copy(filterId = id) } },
            )
            ChipRow(
                title = stringResource(R.string.transition),
                options = TRANSITIONS,
                selectedId = clip.transitionId,
                onSelect = { id ->
                    onUpdate { c ->
                        c.copy(
                            transitionId = id,
                            transitionDurationMs = if (id == null) 0 else 500,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(end = 8.dp),
        )
        Slider(
            value = value,
            onValueChange = onValue,
            onValueChangeFinished = onCommit,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ChipRow(
    title: String,
    options: List<Pair<String, String>>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.none)) },
        )
        options.forEach { (id, name) ->
            FilterChip(
                selected = selectedId == id,
                onClick = { onSelect(id) },
                label = { Text(name) },
            )
        }
    }
}

/** id -> display name; ids match assets/transitions/<id>.glsl */
private val TRANSITIONS = listOf(
    "fade" to "Fade",
    "directionalwipe" to "Wipe",
    "circleopen" to "Circle",
    "wiperight" to "Slide",
)
