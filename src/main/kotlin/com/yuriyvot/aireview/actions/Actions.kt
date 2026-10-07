package com.yuriyvot.aireview.actions

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.yuriyvot.aireview.diffview.ReviewSession
import com.yuriyvot.aireview.git.GitDiff
import com.yuriyvot.aireview.git.GitException
import com.yuriyvot.aireview.util.Notify
import com.yuriyvot.aireview.editor.CommentPopups
import com.yuriyvot.aireview.editor.LineRange
import com.yuriyvot.aireview.git.Git
import com.yuriyvot.aireview.model.ReviewComment
import com.yuriyvot.aireview.store.CommentStore
import com.yuriyvot.aireview.util.Async
import com.yuriyvot.aireview.util.ProjectPaths
import java.io.File

class AddCommentAction : DumbAwareAction() {

    private class EditorTarget(val editor: Editor, val file: VirtualFile, val path: String)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val editorTarget = editorTarget(e, project)
        val files = if (editorTarget == null) fileTargets(e, project) else emptyList()
        e.presentation.isEnabledAndVisible = editorTarget != null || files.isNotEmpty()
        e.presentation.text = when {
            editorTarget != null -> "Комментарий для Claude…"
            files.size == 1 && files[0].first.isDirectory -> "Комментарий для Claude к папке…"
            files.size == 1 -> "Комментарий для Claude к файлу…"
            else -> "Комментарий для Claude к ${files.size} элементам…"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val target = editorTarget(e, project)
        if (target != null) commentLines(project, target) else commentFiles(project, fileTargets(e, project), e)
    }

    private fun editorTarget(e: AnActionEvent, project: Project): EditorTarget? {
        if (e.place == ActionPlaces.EDITOR_TAB_POPUP) return null
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        val path = ProjectPaths.relative(project, file) ?: return null
        return EditorTarget(editor, file, path)
    }

    private fun fileTargets(e: AnActionEvent, project: Project): List<Pair<VirtualFile, String>> {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList()
            ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))
        return files.mapNotNull { file -> ProjectPaths.relative(project, file)?.let { file to it } }
    }

    private fun commentLines(project: Project, target: EditorTarget) {
        val editor = target.editor
        val range = LineRange.of(editor) ?: return
        FileDocumentManager.getInstance().saveDocument(editor.document)
        val popup = CommentPopups.input(project, "Комментарий для Claude", target.file.name, editor = editor, range = range) { text, chosen ->
            val r = chosen ?: range
            Async.background {
                CommentStore.getInstance(project).add(
                    ReviewComment(
                        filePath = target.path,
                        startLine = r.start,
                        endLine = r.end,
                        selectedText = r.text,
                        comment = text,
                        commitHash = head(project),
                    )
                )
            }
        }
        popup.showInBestPositionFor(editor)
    }

    private fun commentFiles(project: Project, targets: List<Pair<VirtualFile, String>>, e: AnActionEvent) {
        if (targets.isEmpty()) return
        val subtitle = if (targets.size == 1) targets[0].second else targets.joinToString(", ") { it.first.name }.take(200)
        val popup = CommentPopups.input(project, "Комментарий для Claude", subtitle) { text, _ ->
            Async.background {
                val head = head(project)
                val store = CommentStore.getInstance(project)
                targets.forEach { (_, path) -> store.add(ReviewComment(filePath = path, comment = text, commitHash = head)) }
            }
        }
        popup.showInBestPositionFor(e.dataContext)
    }

    private fun head(project: Project): String? =
        ReviewSession.findRoot(project)?.let { Git(it).lineOrNull("rev-parse", "HEAD") }
}

class CompareCommitsAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val selection = selection(e)
        e.presentation.isEnabledAndVisible = e.project != null && selection != null
        e.presentation.text = if (selection?.second?.size == 2) "AI Review: сравнить два коммита" else "AI Review: изменения коммита"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (root, hashes) = selection(e) ?: return
        Async.background {
            val ends = try {
                GitDiff.orderCommits(Git(root), hashes)
            } catch (ex: GitException) {
                return@background Notify.error(project, ex.message ?: "Не удалось определить коммиты")
            }
            val session = ReviewSession.getInstance(project)
            session.showRange(ends)
            session.showPane()
        }
    }

    private fun selection(e: AnActionEvent): Pair<File, List<String>>? {
        val selection = e.getData(COMMIT_SELECTION) ?: return null
        return try {
            val loader = selection.javaClass.classLoader
            val selectionClass = Class.forName("com.intellij.vcs.log.VcsLogCommitSelection", false, loader)
            val commits = selectionClass.getMethod("getCommits").invoke(selection) as? List<*> ?: return null
            if (commits.size !in 1..2) return null
            val commitIdClass = Class.forName("com.intellij.vcs.log.CommitId", false, loader)
            val hashClass = Class.forName("com.intellij.vcs.log.Hash", false, loader)
            val getHash = commitIdClass.getMethod("getHash")
            val getRoot = commitIdClass.getMethod("getRoot")
            val asString = hashClass.getMethod("asString")
            val roots = commits.map { getRoot.invoke(it) as VirtualFile }.distinct()
            if (roots.size != 1) return null
            File(roots[0].path) to commits.map { asString.invoke(getHash.invoke(it)) as String }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val COMMIT_SELECTION = DataKey.create<Any>("Vcs.Log.Commit.Selection")
    }
}
