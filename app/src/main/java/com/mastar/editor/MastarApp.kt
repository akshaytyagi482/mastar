package com.mastar.editor

import android.app.Application
import com.mastar.editor.data.db.MastarDatabase
import com.mastar.editor.data.repo.ProjectRepository

/**
 * Application entry point. Keeps a single app-scoped database + repository —
 * deliberately no DI framework yet, to keep APK size and cold-start minimal
 * on budget hardware. Swap for Hilt later if the graph grows.
 */
class MastarApp : Application() {

    val database: MastarDatabase by lazy { MastarDatabase.create(this) }
    val projectRepository: ProjectRepository by lazy { ProjectRepository(database) }
}
