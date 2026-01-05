package com.aireview.actions

import com.aireview.model.ReviewComment
import com.aireview.services.CommentStorageService
import com.aireview.ui.AddCommentDialog
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

class AddCommentAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val selectionModel = editor.selectionModel
        val selectedText = selectionModel.selectedText ?: return
        val document = editor.document

        val startLine = document.getLineNumber(selectionModel.selectionStart) + 1
        val endLine = document.getLineNumber(selectionModel.selectionEnd) + 1

        ApplicationManager.getApplication().invokeLater {
            val dialog = AddCommentDialog(project)
            if (dialog.showAndGet()) {
                val commentText = dialog.commentText
                if (commentText.isNotEmpty()) {
                    val relativePath = getRelativePath(project, file)
                    val commitHash = getGitCommitHash(project)
                    val comment = ReviewComment(
                        filePath = relativePath,
                        startLine = startLine,
                        endLine = endLine,
                        selectedText = selectedText,
                        comment = commentText,
                        commitHash = commitHash
                    )
                    CommentStorageService.getInstance(project).addComment(comment)
                }
            }
        }
    }

    private fun getGitCommitHash(project: Project): String? {
        val basePath = project.basePath ?: return null
        return try {
            val process = ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()
            val result = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (result.length == 40) result else null
        } catch (e: Exception) {
            null
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() == true
        e.presentation.isEnabledAndVisible = e.project != null && hasSelection
    }

    private fun getRelativePath(project: Project, file: VirtualFile): String {
        val basePath = project.basePath ?: return file.path
        val filePath = file.path
        return if (filePath.startsWith(basePath)) {
            filePath.removePrefix(basePath).removePrefix("/").removePrefix("\\")
        } else {
            filePath
        }
    }
}
