package com.mastar.editor.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.mastar.editor.MastarApp
import com.mastar.editor.data.db.ClipType
import com.mastar.editor.data.db.ProjectWithTracks
import com.mastar.editor.ui.theme.CharcoalSurface
import com.mastar.editor.ui.theme.Saffron
import kotlinx.coroutines.launch

/**
 * CapCut-style home: brand header, a big "New project" button, and a grid of
 * project cards with real first-frame thumbnails and durations.
 */
@Composable
fun HomeScreen(onOpenProject: (Long) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MastarApp
    val scope = rememberCoroutineScope()

    val projects by app.projectRepository.observeProjectsWithTracks()
        .collectAsState(initial = emptyList())

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Brand header.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Movie,
                contentDescription = null,
                tint = Saffron,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Mastar",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        // Big CapCut-style "New project" button.
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(84.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(Saffron, Color(0xFFFFB74D))
                    )
                )
                .clickable {
                    scope.launch {
                        val id = app.projectRepository.createProject(
                            name = "Project ${projects.size + 1}",
                            nowMs = System.currentTimeMillis(),
                        )
                        onOpenProject(id)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    "New project",
                    color = Color.Black,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Text(
            "Projects",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )

        if (projects.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Koi project nahi hai — tap New project!",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(projects, key = { it.project.id }) { project ->
                    ProjectCard(
                        project = project,
                        onOpen = { onOpenProject(project.project.id) },
                        onDuplicate = {
                            scope.launch {
                                app.projectRepository.duplicateProject(
                                    project.project.id, System.currentTimeMillis()
                                )
                            }
                        },
                        onDelete = {
                            scope.launch {
                                app.projectRepository.deleteProject(project.project.id)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(
    project: ProjectWithTracks,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val clips = project.tracks.flatMap { it.clips }
    val firstVideo = clips.filter { it.type == ClipType.VIDEO }.minByOrNull { it.timelineStartMs }
    val durationMs = clips.filter { it.type == ClipType.VIDEO }.maxOfOrNull { it.timelineEndMs } ?: 0L

    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CharcoalSurface)
            .clickable(onClick = onOpen),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(Color(0xFF1A1A1A)),
        ) {
            if (firstVideo != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(firstVideo.sourceUri)
                        .videoFrameMillis(firstVideo.sourceStartMs)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.Movie,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.25f),
                    modifier = Modifier
                        .size(32.dp)
                        .align(Alignment.Center),
                )
            }
            Text(
                formatDuration(durationMs),
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                project.project.name,
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            IconButton(onClick = onDuplicate, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Duplicate project",
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(13.dp),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete project",
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    return "%02d:%02d".format(totalSec / 60, totalSec % 60)
}
