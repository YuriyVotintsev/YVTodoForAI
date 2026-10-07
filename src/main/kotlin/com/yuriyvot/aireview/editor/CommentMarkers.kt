package com.yuriyvot.aireview.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.yuriyvot.aireview.model.CommentStatus
import com.yuriyvot.aireview.model.ReviewComment
import com.yuriyvot.aireview.store.CommentStore
import com.yuriyvot.aireview.util.Async
import com.yuriyvot.aireview.util.ProjectPaths
import java.awt.Color
import java.awt.event.MouseEvent
import javax.swing.Icon

class CommentMarkersStartup : ProjectActivity, DumbAware {
    override suspend fun execute(project: Project) {
        project.service<CommentMarkers>().start()
    }
}

@Service(Service.Level.PROJECT)
class CommentMarkers(private val project: Project) : Disposable {
    private val markersKey = Key.create<List<RangeHighlighter>>("aiReview.markers")

    fun start() {
        val store = CommentStore.getInstance(project)
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project == project) refresh(event.editor, store.comments())
            }

            override fun editorReleased(event: EditorFactoryEvent) = clear(event.editor)
        }, this)
        project.messageBus.connect(this).subscribe(CommentStore.TOPIC, CommentStore.Listener { refreshAll() })
        Async.edt(project, anyModality = true) { refreshAll() }
    }

    private fun refreshAll() {
        val comments = CommentStore.getInstance(project).comments()
        EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach { refresh(it, comments) }
    }

    private fun clear(editor: Editor) {
        editor.getUserData(markersKey)?.forEach { editor.markupModel.removeHighlighter(it) }
        editor.putUserData(markersKey, null)
    }

    private fun refresh(editor: Editor, all: List<ReviewComment>) {
        clear(editor)
        if (editor.isDisposed) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val path = ProjectPaths.relative(project, file) ?: return
        val document = editor.document
        val markers = all
            .filter { it.filePath == path && it.startLine != null && it.revision == null && it.status.isOpen }
            .mapNotNull { comment ->
                val lastLine = document.lineCount - 1
                if (lastLine < 0) return@mapNotNull null
                val start = (comment.startLine!! - 1).coerceIn(0, lastLine)
                val end = ((comment.endLine ?: comment.startLine) - 1).coerceIn(start, lastLine)
                val color = if (comment.status == CommentStatus.HAS_QUESTIONS) QUESTION_COLOR else PENDING_COLOR
                val attributes = TextAttributes().apply {
                    backgroundColor = color
                    errorStripeColor = STRIPE_COLOR
                }
                editor.markupModel.addRangeHighlighter(
                    document.getLineStartOffset(start),
                    document.getLineEndOffset(end),
                    HighlighterLayer.SELECTION - 1,
                    attributes,
                    HighlighterTargetArea.LINES_IN_RANGE,
                ).apply {
                    isThinErrorStripeMark = true
                    errorStripeTooltip = comment.comment
                    gutterIconRenderer = CommentGutterIcon(project, comment)
                }
            }
        if (markers.isNotEmpty()) editor.putUserData(markersKey, markers)
    }

    override fun dispose() {
        EditorFactory.getInstance().allEditors.forEach { clear(it) }
    }

    private class CommentGutterIcon(private val project: Project, private val comment: ReviewComment) : GutterIconRenderer(), DumbAware {
        override fun getIcon(): Icon =
            if (comment.status == CommentStatus.HAS_QUESTIONS) AllIcons.General.BalloonWarning else AllIcons.General.Balloon

        override fun getTooltipText(): String =
            "<html><b>Claude</b> · ${CommentPopups.statusTitle(comment.status)}<br>" +
                StringUtil.escapeXmlEntities(comment.comment).replace("\n", "<br>") + "</html>"

        override fun isNavigateAction(): Boolean = true

        override fun getAlignment(): Alignment = Alignment.RIGHT

        override fun getClickAction(): AnAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                CommentPopups.details(project, comment, e.inputEvent as? MouseEvent, e.getData(CommonDataKeys.EDITOR))
            }
        }

        override fun equals(other: Any?): Boolean = other is CommentGutterIcon && other.comment == comment

        override fun hashCode(): Int = comment.hashCode()
    }

    companion object {
        private val PENDING_COLOR = JBColor(Color(255, 214, 0, 40), Color(255, 200, 40, 26))
        private val QUESTION_COLOR = JBColor(Color(255, 140, 0, 46), Color(255, 140, 40, 34))
        private val STRIPE_COLOR = JBColor(Color(230, 170, 0), Color(220, 170, 40))
    }
}
