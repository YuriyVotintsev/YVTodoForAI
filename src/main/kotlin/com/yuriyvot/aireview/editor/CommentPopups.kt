package com.yuriyvot.aireview.editor

import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.yuriyvot.aireview.model.CommentStatus
import com.yuriyvot.aireview.model.ReviewComment
import com.yuriyvot.aireview.store.CommentStore
import com.yuriyvot.aireview.toolwindow.ReviewUi
import com.yuriyvot.aireview.util.Async
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.KeyStroke

/** 1-based inclusive line range of an editor document together with its text. */
data class LineRange(val start: Int, val end: Int, val text: String) {
    val label: String get() = if (end > start) "Строки $start–$end" else "Строка $start"

    companion object {
        /** The selected lines of [editor] (or the caret line when nothing is selected). */
        fun of(editor: Editor): LineRange? {
            val document = editor.document
            if (document.lineCount == 0) return null
            val selection = editor.selectionModel
            if (!selection.hasSelection()) {
                val line = editor.caretModel.logicalPosition.line.coerceIn(0, document.lineCount - 1)
                val text = document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))
                return LineRange(line + 1, line + 1, text)
            }
            val start = document.getLineNumber(selection.selectionStart)
            var end = document.getLineNumber(selection.selectionEnd)
            if (end > start && selection.selectionEnd == document.getLineStartOffset(end)) end--
            return LineRange(start + 1, end + 1, selection.selectedText ?: "")
        }
    }
}

object CommentPopups {

    /**
     * A non-modal comment input. With [editor] and [range] the popup also shows the commented lines and their code;
     * selecting other lines in the editor while the popup is open moves the comment to them.
     */
    fun input(
        project: Project,
        title: String,
        subtitle: String?,
        initial: String = "",
        editor: Editor? = null,
        range: LineRange? = null,
        onSubmit: (String, LineRange?) -> Unit,
    ): JBPopup {
        val area = JBTextArea(initial, 6, 60).apply {
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(6, 8)
        }
        val scroll = JBScrollPane(area).apply { preferredSize = JBUI.size(560, 140) }
        val hint = JBLabel("Ctrl+Enter — сохранить · Esc — отмена").apply {
            foreground = NamedColorUtil.getInactiveTextColor()
            font = JBFont.small()
        }
        val save = JButton("Сохранить")
        val cancel = JButton("Отмена")
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(cancel)
            add(save)
        }
        val bottom = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(hint, BorderLayout.WEST)
            add(buttons, BorderLayout.EAST)
        }

        var current = range
        val rangeLabel = JBLabel().apply { foreground = NamedColorUtil.getInactiveTextColor() }
        val code = JBTextArea().apply {
            isEditable = false
            rows = 5
            font = editor?.colorsScheme?.getFont(EditorFontType.PLAIN) ?: font
            border = JBUI.Borders.empty(4, 8)
        }
        val codeScroll = JBScrollPane(code).apply { preferredSize = JBUI.size(560, 110) }
        fun showRange() {
            val r = current ?: return
            rangeLabel.text = "${r.label} · выделите другие строки в редакторе, чтобы перенести комментарий"
            code.text = r.text
            code.caretPosition = 0
        }
        showRange()

        val panel = JPanel(VerticalLayout(JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(8)
            if (subtitle != null) add(JBLabel(subtitle).apply { foreground = NamedColorUtil.getInactiveTextColor() })
            add(scroll)
            if (editor != null && range != null) {
                add(rangeLabel)
                add(codeScroll)
            }
            add(bottom)
        }

        var submitted = false
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, area)
            .setTitle(title)
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(false)
            .setCancelOnWindowDeactivation(false)
            .setCancelKeyEnabled(true)
            .setDimensionServiceKey(project, "AIReview.CommentInput2", false)
            .setCancelCallback {
                submitted || (area.text.trim() == initial.trim() && current == range) ||
                    MessageDialogBuilder.yesNo("Закрыть комментарий?", "Изменения будут потеряны.").ask(panel)
            }
            .createPopup()

        if (editor != null && range != null) {
            editor.selectionModel.addSelectionListener(object : SelectionListener {
                override fun selectionChanged(e: SelectionEvent) {
                    if (!editor.selectionModel.hasSelection()) return
                    current = LineRange.of(editor) ?: return
                    showRange()
                }
            }, popup)
        }

        fun submit() {
            val text = area.text.trim()
            if (text.isEmpty()) return
            submitted = true
            popup.closeOk(null)
            onSubmit(text, current)
        }
        save.addActionListener { submit() }
        cancel.addActionListener { popup.cancel() }
        DumbAwareAction.create { submit() }.registerCustomShortcutSet(
            CustomShortcutSet(
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), null),
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK), null),
            ),
            area,
            popup,
        )
        return popup
    }

    fun details(project: Project, comment: ReviewComment, event: MouseEvent?, editor: Editor? = null) {
        val date = SimpleDateFormat("dd.MM HH:mm").format(Date(comment.createdAt))
        val header = JBLabel("${statusTitle(comment.status)} · $date").apply {
            foreground = NamedColorUtil.getInactiveTextColor()
            font = JBFont.small()
        }
        val text = JBTextArea(comment.comment).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            border = JBUI.Borders.empty(4, 0)
            columns = 48
        }
        val edit = JButton("Изменить")
        val done = JButton("Выполнен")
        val delete = JButton("Удалить")
        val inList = JButton("В списке")
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(edit)
            if (comment.status.isOpen) add(done)
            add(delete)
            add(inList)
        }
        val panel = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(10)
            add(header, BorderLayout.NORTH)
            add(text, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, edit)
            .setTitle("Комментарий для Claude")
            .setRequestFocus(true)
            .setMovable(true)
            .setResizable(true)
            .setCancelOnClickOutside(true)
            .createPopup()
        val store = CommentStore.getInstance(project)
        edit.addActionListener {
            popup.cancel()
            val start = comment.startLine
            val range = if (editor != null && start != null && comment.revision == null) {
                LineRange(start, comment.endLine ?: start, comment.selectedText ?: "")
            } else {
                null
            }
            val input = input(project, "Изменить комментарий", null, comment.comment, editor, range) { newText, newRange ->
                Async.background {
                    store.update(comment.id) {
                        if (newRange == null || newRange == range) {
                            it.copy(comment = newText)
                        } else {
                            it.copy(
                                comment = newText,
                                startLine = newRange.start,
                                endLine = newRange.end,
                                selectedText = newRange.text,
                                diffSnippet = null,
                            )
                        }
                    }
                }
            }
            if (event != null) input.show(RelativePoint(event)) else input.showInFocusCenter()
        }
        done.addActionListener {
            popup.cancel()
            Async.background { store.update(comment.id) { it.copy(status = CommentStatus.DONE) } }
        }
        delete.addActionListener {
            popup.cancel()
            if (MessageDialogBuilder.yesNo("Удалить комментарий?", comment.comment.take(300)).ask(project)) {
                Async.background { store.remove(comment.id) }
            }
        }
        inList.addActionListener {
            popup.cancel()
            ReviewUi.getInstance(project).showInList(comment.id)
        }
        if (event != null) popup.show(RelativePoint(event)) else popup.showInFocusCenter()
    }

    fun statusTitle(status: CommentStatus): String = when (status) {
        CommentStatus.PENDING -> "Новый"
        CommentStatus.IN_PROGRESS -> "В работе"
        CommentStatus.HAS_QUESTIONS -> "Есть вопросы"
        CommentStatus.DONE -> "Выполнен"
        CommentStatus.CANCELED -> "Отменён"
        CommentStatus.CLOSED -> "Завершён"
    }
}
