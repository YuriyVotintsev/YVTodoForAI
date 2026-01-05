package com.aireview.toolwindow

import com.aireview.editor.CommentNavigator
import com.aireview.model.CommentStatus
import com.aireview.model.ReviewComment
import com.aireview.services.CommentStorageService
import com.aireview.ui.AddCommentDialog
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*
import javax.swing.event.ListSelectionListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer

class CommentListPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val storage = CommentStorageService.getInstance(project)
    private val tableModel = CommentTableModel()
    private val table = JBTable(tableModel)

    private var filterStatus: CommentStatus? = null
    private var comments = listOf<ReviewComment>()
    private var codePanelPosition = CodePanelPosition.RIGHT

    private enum class CodePanelPosition { BOTTOM, RIGHT, HIDDEN }

    private val codeArea = JTextArea().apply {
        isEditable = false
        font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
    }
    private val codePanel = JPanel(BorderLayout()).apply {
        add(JBScrollPane(codeArea), BorderLayout.CENTER)
    }
    private val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)

    private val tableScrollPane = JBScrollPane(table)
    private lateinit var togglePositionButton: JButton

    init {
        setupTable()
        setupToolbar()
        setupSplitPane()

        storage.addChangeListener { refreshList() }
        refreshList()

        CommentNavigator.registerSelectCallback { commentId ->
            selectCommentById(commentId)
        }
    }

    private fun selectCommentById(commentId: String) {
        val index = comments.indexOfFirst { it.id == commentId }
        if (index >= 0) {
            table.setRowSelectionInterval(index, index)
            table.scrollRectToVisible(table.getCellRect(index, 0, true))
        }
    }

    private fun setupSplitPane() {
        splitPane.leftComponent = tableScrollPane
        splitPane.rightComponent = codePanel
        splitPane.resizeWeight = 0.5
        splitPane.dividerSize = 8
        splitPane.border = null
        splitPane.isContinuousLayout = true
        splitPane.ui = object : javax.swing.plaf.basic.BasicSplitPaneUI() {
            override fun createDefaultDivider(): javax.swing.plaf.basic.BasicSplitPaneDivider {
                return object : javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                    override fun paint(g: java.awt.Graphics) {
                        g.color = com.intellij.ui.JBColor.border()
                        g.fillRect(0, 0, width, height)
                    }
                }
            }
        }
        codePanel.minimumSize = java.awt.Dimension(100, 80)
        tableScrollPane.minimumSize = java.awt.Dimension(100, 80)
        add(splitPane, BorderLayout.CENTER)
    }

    private fun setupTable() {
        table.setShowGrid(false)
        table.intercellSpacing = java.awt.Dimension(0, 0)
        table.tableHeader.isVisible = false
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

        table.columnModel.getColumn(0).apply {
            preferredWidth = 24
            maxWidth = 24
            minWidth = 24
            cellRenderer = StatusButtonRenderer()
        }
        table.columnModel.getColumn(1).apply {
            preferredWidth = 24
            maxWidth = 24
            minWidth = 24
            cellRenderer = EditButtonRenderer()
        }
        table.columnModel.getColumn(2).cellRenderer = CommentCellRenderer()

        table.selectionModel.addListSelectionListener(ListSelectionListener {
            if (!it.valueIsAdjusting) {
                updateCodePanel()
            }
        })

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val col = table.columnAtPoint(e.point)
                if (row < 0 || row >= comments.size) return

                val comment = comments[row]
                when (col) {
                    0 -> showStatusPopup(e, comment)
                    1 -> editComment(comment)
                    2 -> if (e.clickCount == 2) navigateToComment(comment)
                }
            }
        })

        table.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                updateRowHeights()
            }
        })
    }

    private fun editComment(comment: ReviewComment) {
        val isGeneral = !comment.isCodeComment()
        val dialog = AddCommentDialog(
            project,
            comment.comment,
            showContextField = isGeneral,
            initialContext = if (isGeneral) comment.selectedText ?: "" else ""
        )
        dialog.title = "Edit Review Comment"
        if (dialog.showAndGet()) {
            val newText = dialog.commentText
            if (newText.isNotEmpty()) {
                if (isGeneral) {
                    val context = dialog.contextText.ifEmpty { null }
                    storage.updateComment(comment.id) { it.copy(comment = newText, selectedText = context) }
                } else {
                    storage.updateComment(comment.id) { it.copy(comment = newText) }
                }
            }
        }
    }

    private fun updateCodePanel() {
        if (codePanelPosition == CodePanelPosition.HIDDEN) return
        val row = table.selectedRow
        if (row >= 0 && row < comments.size) {
            val comment = comments[row]
            val selectedText = comment.selectedText
            if (selectedText != null && selectedText.isNotEmpty()) {
                codeArea.text = selectedText
            } else {
                codeArea.text = "(No code attached)"
            }
        } else {
            codeArea.text = ""
        }
    }

    private fun toggleCodePanelPosition() {
        codePanelPosition = when (codePanelPosition) {
            CodePanelPosition.BOTTOM -> CodePanelPosition.RIGHT
            CodePanelPosition.RIGHT -> CodePanelPosition.HIDDEN
            CodePanelPosition.HIDDEN -> CodePanelPosition.BOTTOM
        }
        applyCodePanelPosition()
    }

    private fun applyCodePanelPosition() {
        when (codePanelPosition) {
            CodePanelPosition.BOTTOM -> {
                splitPane.orientation = JSplitPane.VERTICAL_SPLIT
                splitPane.topComponent = tableScrollPane
                splitPane.bottomComponent = codePanel
                splitPane.resizeWeight = 0.7
                codePanel.isVisible = true
                togglePositionButton.text = "▯"
                togglePositionButton.toolTipText = "Move code panel to right"
                forceUpdateCodePanel()
            }
            CodePanelPosition.RIGHT -> {
                splitPane.orientation = JSplitPane.HORIZONTAL_SPLIT
                splitPane.leftComponent = tableScrollPane
                splitPane.rightComponent = codePanel
                splitPane.resizeWeight = 0.5
                codePanel.isVisible = true
                togglePositionButton.text = "✕"
                togglePositionButton.toolTipText = "Hide code panel"
                forceUpdateCodePanel()
            }
            CodePanelPosition.HIDDEN -> {
                codePanel.isVisible = false
                togglePositionButton.text = "▭"
                togglePositionButton.toolTipText = "Show code panel (bottom)"
            }
        }
        revalidate()
        repaint()
    }

    private fun forceUpdateCodePanel() {
        val row = table.selectedRow
        if (row >= 0 && row < comments.size) {
            val comment = comments[row]
            val selectedText = comment.selectedText
            if (selectedText != null && selectedText.isNotEmpty()) {
                codeArea.text = selectedText
            } else {
                codeArea.text = "(No code attached)"
            }
        } else {
            codeArea.text = ""
        }
    }

    private fun confirmAndDelete(comment: ReviewComment) {
        val result = JOptionPane.showConfirmDialog(
            this,
            "Delete this comment?",
            "Confirm Delete",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )
        if (result == JOptionPane.YES_OPTION) {
            storage.removeComment(comment.id)
        }
    }

    private fun formatStatusName(status: CommentStatus): String {
        return when (status) {
            CommentStatus.PENDING -> "Pending"
            CommentStatus.HAS_QUESTIONS -> "Has Questions"
            CommentStatus.DONE -> "Done"
            CommentStatus.CANCELED -> "Canceled"
        }
    }

    private fun showStatusPopup(e: MouseEvent, comment: ReviewComment) {
        val popup = JPopupMenu()
        CommentStatus.entries.forEach { status ->
            val item = JMenuItem(formatStatusName(status))
            if (comment.status == status) {
                item.isEnabled = false
            }
            item.addActionListener {
                storage.updateComment(comment.id) { it.copy(status = status) }
            }
            popup.add(item)
        }
        popup.addSeparator()
        val deleteItem = JMenuItem("Delete")
        deleteItem.foreground = java.awt.Color(220, 80, 80)
        deleteItem.addActionListener {
            confirmAndDelete(comment)
        }
        popup.add(deleteItem)
        popup.show(table, e.x, e.y)
    }

    private fun setupToolbar() {
        val toolbar = JPanel()
        toolbar.layout = BoxLayout(toolbar, BoxLayout.X_AXIS)

        val filterCombo = JComboBox(arrayOf("All", "Pending", "Has Questions", "Done", "Canceled"))
        filterCombo.addActionListener {
            filterStatus = when (filterCombo.selectedIndex) {
                1 -> CommentStatus.PENDING
                2 -> CommentStatus.HAS_QUESTIONS
                3 -> CommentStatus.DONE
                4 -> CommentStatus.CANCELED
                else -> null
            }
            refreshList()
        }

        val addCommentButton = JButton("+")
        addCommentButton.toolTipText = "Add general comment"
        addCommentButton.preferredSize = java.awt.Dimension(28, 28)
        addCommentButton.minimumSize = java.awt.Dimension(28, 28)
        addCommentButton.maximumSize = java.awt.Dimension(28, 28)
        addCommentButton.margin = java.awt.Insets(0, 0, 0, 0)
        addCommentButton.addActionListener {
            addGeneralComment()
        }

        val openJsonButton = JButton("Open JSON")
        openJsonButton.addActionListener {
            openJsonFile()
        }

        val clearDoneButton = JButton("Clear Done")
        clearDoneButton.addActionListener {
            storage.clearDone()
        }

        togglePositionButton = JButton("✕")
        togglePositionButton.toolTipText = "Hide code panel"
        togglePositionButton.preferredSize = java.awt.Dimension(28, 28)
        togglePositionButton.minimumSize = java.awt.Dimension(28, 28)
        togglePositionButton.maximumSize = java.awt.Dimension(28, 28)
        togglePositionButton.margin = java.awt.Insets(0, 0, 0, 0)
        togglePositionButton.addActionListener {
            toggleCodePanelPosition()
        }

        toolbar.add(filterCombo)
        toolbar.add(Box.createHorizontalStrut(8))
        toolbar.add(addCommentButton)
        toolbar.add(Box.createHorizontalStrut(8))
        toolbar.add(openJsonButton)
        toolbar.add(Box.createHorizontalStrut(8))
        toolbar.add(clearDoneButton)
        toolbar.add(Box.createHorizontalStrut(8))
        toolbar.add(togglePositionButton)
        toolbar.add(Box.createHorizontalGlue())

        add(toolbar, BorderLayout.NORTH)
    }

    private fun refreshList() {
        SwingUtilities.invokeLater {
            comments = storage.getComments()
                .let { list ->
                    filterStatus?.let { status -> list.filter { it.status == status } } ?: list
                }
                .sortedByDescending { it.createdAt }
            tableModel.fireTableDataChanged()
            updateRowHeights()
            updateCodePanel()
        }
    }

    private fun updateRowHeights() {
        if (comments.isEmpty()) return
        val fontMetrics = table.getFontMetrics(table.font)
        val lineHeight = fontMetrics.height
        val columnWidth = table.columnModel.getColumn(2).width - 16

        comments.forEachIndexed { index, comment ->
            val locationInfo = if (comment.isCodeComment()) {
                val fileName = File(comment.filePath!!).name
                "$fileName:${comment.startLine} - "
            } else {
                "[General] "
            }
            val questionsInfo = comment.questionsOrEmpty().let { q ->
                if (q.isNotEmpty()) " [${q.count { it.answer != null }}/${q.size}]" else ""
            }
            val fullText = "$locationInfo${comment.comment}$questionsInfo"

            val explicitLines = fullText.lines().size
            val wrapLines = if (columnWidth > 0) {
                fullText.lines().sumOf { line ->
                    val textWidth = fontMetrics.stringWidth(line)
                    ((textWidth / columnWidth.toFloat()) + 1).toInt().coerceAtLeast(1)
                }
            } else {
                explicitLines
            }

            val height = (wrapLines * lineHeight + 12).coerceAtLeast(28)
            table.setRowHeight(index, height)
        }
    }

    private fun openJsonFile() {
        val basePath = project.basePath ?: return
        val jsonPath = "$basePath/.ai-review-comments.json".replace("\\", "/")
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(jsonPath) ?: return
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
    }

    private fun navigateToComment(comment: ReviewComment) {
        val filePath = comment.filePath ?: return
        val startLine = comment.startLine ?: return
        val basePath = project.basePath ?: return
        val fullPath = File(basePath, filePath).absolutePath.replace("\\", "/")
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath) ?: return

        val descriptor = OpenFileDescriptor(project, virtualFile, startLine - 1, 0)
        FileEditorManager.getInstance(project).openEditor(descriptor, true)
    }

    private fun addGeneralComment() {
        val dialog = AddCommentDialog(project, "", showContextField = true)
        dialog.title = "Add General Comment"
        if (dialog.showAndGet()) {
            val text = dialog.commentText
            if (text.isNotEmpty()) {
                val context = dialog.contextText.ifEmpty { null }
                val comment = ReviewComment(
                    filePath = null,
                    startLine = null,
                    endLine = null,
                    selectedText = context,
                    comment = text
                )
                storage.addComment(comment)
            }
        }
    }

    private inner class CommentTableModel : AbstractTableModel() {
        override fun getRowCount() = comments.size
        override fun getColumnCount() = 3
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = comments[rowIndex]
    }

    private inner class StatusButtonRenderer : TableCellRenderer {
        private val label = JLabel().apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.TOP
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
        }

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val comment = value as? ReviewComment ?: return label

            val (icon, color) = when (comment.status) {
                CommentStatus.DONE -> "✓" to java.awt.Color.GRAY
                CommentStatus.CANCELED -> "✗" to java.awt.Color(220, 80, 80)
                CommentStatus.HAS_QUESTIONS -> "?" to java.awt.Color(255, 165, 0)
                CommentStatus.PENDING -> "○" to null
            }

            label.text = icon
            label.foreground = color ?: (if (isSelected) table.selectionForeground else table.foreground)
            label.background = if (isSelected) table.selectionBackground else table.background
            label.isOpaque = true

            return label
        }
    }

    private inner class EditButtonRenderer : TableCellRenderer {
        private val label = JLabel("✎").apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.TOP
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
        }

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            label.foreground = if (isSelected) table.selectionForeground else table.foreground
            label.background = if (isSelected) table.selectionBackground else table.background
            label.isOpaque = true
            return label
        }
    }

    private inner class CommentCellRenderer : TableCellRenderer {
        private val panel = JPanel(BorderLayout())
        private val textArea = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        }

        init {
            panel.add(textArea, BorderLayout.CENTER)
        }

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val comment = value as? ReviewComment ?: return panel

            val locationInfo = if (comment.isCodeComment()) {
                val fileName = File(comment.filePath!!).name
                "$fileName:${comment.startLine} - "
            } else {
                "[General] "
            }

            val questionsInfo = comment.questionsOrEmpty().let { q ->
                if (q.isNotEmpty()) " [${q.count { it.answer != null }}/${q.size}]" else ""
            }

            textArea.text = "$locationInfo${comment.comment}$questionsInfo"
            textArea.font = table.font
            textArea.foreground = if (isSelected) table.selectionForeground else table.foreground
            panel.background = if (isSelected) table.selectionBackground else table.background
            panel.toolTipText = comment.filePath ?: "General comment"

            return panel
        }
    }
}
