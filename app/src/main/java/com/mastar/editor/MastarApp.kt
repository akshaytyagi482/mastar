package com.mastar.editor

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.mastar.editor.data.db.MastarDatabase
import com.mastar.editor.data.repo.ProjectRepository

/**
 * Application entry point. Keeps a single app-scoped database + repository —
 * deliberately no DI framework yet, to keep APK size and cold-start minimal
 * on budget hardware. Swap for Hilt later if the graph grows.
 */
class MastarApp : Application(), ImageLoaderFactory {

    val database: MastarDatabase by lazy { MastarDatabase.create(this) }
    val projectRepository: ProjectRepository by lazy { ProjectRepository(database) }

    /**
     * App-wide Coil loader that can decode video frames — this powers the
     * CapCut-style filmstrip thumbnails on timeline clips and project cards.
     * Aggressive memory/disk caching keeps timeline scrolling at 60fps.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(false)
            .respectCacheHeaders(false)
            .build()
}
