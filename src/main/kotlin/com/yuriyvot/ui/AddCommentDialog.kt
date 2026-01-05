package com.yuriyvot.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

class AddCommentDialog(
    project: Project?,
    initialText: String = "",
    private val showContextField: Boolean = false,
    initialContext: String = ""
) : DialogWrapper(project) {

    private val textArea = JTextArea(initialText).apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 4
        columns = 40
    }

    private val contextArea = JTextArea(initialContext).apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 6
        columns = 40
        font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
    }

    val commentText: String
        get() = textArea.text.trim()

    val contextText: String
        get() = contextArea.text.trim()

    init {
        title = "Add Review Comment"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10)

        val commentPanel = JPanel(BorderLayout())
        val commentLabel = JLabel("Comment:")
        commentLabel.border = JBUI.Borders.emptyBottom(4)
        commentPanel.add(commentLabel, BorderLayout.NORTH)
        val scrollPane = JBScrollPane(textArea)
        scrollPane.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBUI.CurrentTheme.Focus.defaultButtonColor(), 1),
            JBUI.Borders.empty(5)
        )
        commentPanel.add(scrollPane, BorderLayout.CENTER)

        if (showContextField) {
            panel.preferredSize = Dimension(500, 300)
            val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT)
            splitPane.topComponent = commentPanel

            val contextPanel = JPanel(BorderLayout())
            val contextLabel = JLabel("Context (error log, stack trace, etc.):")
            contextLabel.border = JBUI.Borders.empty(8, 0, 4, 0)
            contextPanel.add(contextLabel, BorderLayout.NORTH)
            val contextScrollPane = JBScrollPane(contextArea)
            contextScrollPane.border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBUI.CurrentTheme.Focus.defaultButtonColor(), 1),
                JBUI.Borders.empty(5)
            )
            contextPanel.add(contextScrollPane, BorderLayout.CENTER)

            splitPane.bottomComponent = contextPanel
            splitPane.resizeWeight = 0.3
            splitPane.border = null
            panel.add(splitPane, BorderLayout.CENTER)
        } else {
            panel.preferredSize = Dimension(450, 150)
            panel.add(commentPanel, BorderLayout.CENTER)
        }

        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = textArea
}
