package com.yuriyvot.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent

class StatusFileWatcher : ProjectActivity {

    override suspend fun execute(project: Project) {
        val connection = project.messageBus.connect()

        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val basePath = project.basePath ?: return

                val hasRelevantChange = events.any { event ->
                    event.path?.endsWith(".ai-review-comments.json") == true &&
                            event.path?.startsWith(basePath) == true
                }

                if (hasRelevantChange) {
                    CommentStorageService.getInstance(project).reloadFromDisk()
                }
            }
        })
    }
}
