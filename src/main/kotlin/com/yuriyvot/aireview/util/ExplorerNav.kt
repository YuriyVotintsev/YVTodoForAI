package com.yuriyvot.aireview.util

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

object ExplorerNav {
    private const val UNITY_PANE = "UnityExplorer"
    private const val FILE_SYSTEM_PANE = "FileSystemView"

    /** Shows [target] (or its nearest existing parent) in the ordinary tree: Unity for Assets/Packages, else File System. */
    fun showInFolder(project: Project, target: File) {
        Async.background {
            var file: File? = target
            while (file != null && !file.exists()) file = file.parentFile
            val vf = file?.let { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) } ?: return@background
            Async.edt(project) {
                val view = ProjectView.getInstance(project)
                val relative = ProjectPaths.relative(project, vf).orEmpty()
                val unity = view.getProjectViewPaneById(UNITY_PANE) != null &&
                    (relative == "Assets" || relative.startsWith("Assets/") || relative.startsWith("Packages/"))
                val pane = when {
                    unity -> UNITY_PANE
                    view.getProjectViewPaneById(FILE_SYSTEM_PANE) != null -> FILE_SYSTEM_PANE
                    else -> view.defaultViewId
                }
                view.changeViewCB(pane, null).doWhenProcessed { view.select(null, vf, true) }
            }
        }
    }
}
