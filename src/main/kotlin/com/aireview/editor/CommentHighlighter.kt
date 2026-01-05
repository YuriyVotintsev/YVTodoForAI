package com.aireview.editor

import com.aireview.model.CommentStatus
import com.aireview.model.ReviewComment
import com.aireview.services.CommentStorageService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
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
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon

class CommentHighlighter : ProjectActivity {

    override suspend fun execute(project: Project) {
        val storage = CommentStorageService.getInstance(project)
        val editorHighlighters = ConcurrentHashMap<Editor, MutableList<RangeHighlighter>>()

        val listener = object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                val editor = event.editor
                if (editor.project != project) return
                updateHighlights(editor, storage, editorHighlighters, project)
            }

            override fun editorReleased(event: EditorFactoryEvent) {
                editorHighlighters.remove(event.editor)
            }
        }

        EditorFactory.getInstance().addEditorFactoryListener(listener, project)

        storage.addChangeListener {
            EditorFactory.getInstance().allEditors
                .filter { it.project == project }
                .forEach { editor ->
                    updateHighlights(editor, storage, editorHighlighters, project)
                }
        }

        EditorFactory.getInstance().allEditors
            .filter { it.project == project }
            .forEach { editor ->
                updateHighlights(editor, storage, editorHighlighters, project)
            }
    }

    private fun updateHighlights(
        editor: Editor,
        storage: CommentStorageService,
        editorHighlighters: ConcurrentHashMap<Editor, MutableList<RangeHighlighter>>,
        project: Project
    ) {
        editorHighlighters[editor]?.forEach { highlighter ->
            editor.markupModel.removeHighlighter(highlighter)
        }
        editorHighlighters[editor] = mutableListOf()

        val virtualFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val basePath = project.basePath ?: return
        val relativePath = virtualFile.path.removePrefix(basePath).removePrefix("/").removePrefix("\\")

        val comments = storage.getCommentsForFile(relativePath)
            .filter { it.status != CommentStatus.DONE }

        val highlighters = mutableListOf<RangeHighlighter>()

        for (comment in comments) {
            if (!comment.isCodeComment()) continue
            val startLine = (comment.startLine!! - 1).coerceAtLeast(0)
            val endLine = ((comment.endLine ?: comment.startLine!!) - 1).coerceAtMost(editor.document.lineCount - 1)

            if (startLine > editor.document.lineCount - 1) continue

            val startOffset = editor.document.getLineStartOffset(startLine)
            val endOffset = editor.document.getLineEndOffset(endLine)

            val attributes = TextAttributes().apply {
                backgroundColor = HIGHLIGHT_COLOR
            }

            val highlighter = editor.markupModel.addRangeHighlighter(
                startOffset,
                endOffset,
                HighlighterLayer.SELECTION - 1,
                attributes,
                HighlighterTargetArea.LINES_IN_RANGE
            )

            highlighter.gutterIconRenderer = CommentGutterIcon(comment, project)
            highlighters.add(highlighter)
        }

        editorHighlighters[editor] = highlighters
    }

    private class CommentGutterIcon(
        private val comment: ReviewComment,
        private val project: Project
    ) : GutterIconRenderer() {

        override fun getIcon(): Icon = CommentIcon

        override fun getTooltipText(): String = comment.comment

        override fun isNavigateAction(): Boolean = true

        override fun getClickAction(): AnAction = object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("AI Review")
                toolWindow?.show {
                    CommentNavigator.selectComment(project, comment.id)
                }
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is CommentGutterIcon) return false
            return comment.id == other.comment.id
        }

        override fun hashCode(): Int = comment.id.hashCode()
    }

    private object CommentIcon : Icon {
        override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
            val g2 = g.create() as java.awt.Graphics2D
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON
            )
            g2.color = JBColor(Color(255, 180, 50), Color(200, 140, 40))
            g2.fillOval(x + 2, y + 2, 12, 12)
            g2.color = JBColor(Color(180, 120, 30), Color(140, 100, 30))
            g2.drawOval(x + 2, y + 2, 12, 12)
            g2.dispose()
        }

        override fun getIconWidth(): Int = 16
        override fun getIconHeight(): Int = 16
    }

    companion object {
        private val HIGHLIGHT_COLOR = JBColor(
            Color(255, 255, 200, 40),
            Color(100, 100, 50, 40)
        )
    }
}

object CommentNavigator {
    private var selectCallback: ((String) -> Unit)? = null

    fun registerSelectCallback(callback: (String) -> Unit) {
        selectCallback = callback
    }

    fun selectComment(project: Project, commentId: String) {
        selectCallback?.invoke(commentId)
    }
}
