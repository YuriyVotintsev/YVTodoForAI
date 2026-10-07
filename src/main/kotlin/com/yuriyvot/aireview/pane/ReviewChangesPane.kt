package com.yuriyvot.aireview.pane

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.UISettings
import com.intellij.ide.SelectInContext
import com.intellij.ide.SelectInTarget
import com.intellij.ide.dnd.aware.DnDAwareTree
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.impl.AbstractProjectViewPane
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.ActionCallback
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.RowIcon
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import com.yuriyvot.aireview.diffview.FileItem
import com.yuriyvot.aireview.diffview.FileTreeDir
import com.yuriyvot.aireview.diffview.ReviewFile
import com.yuriyvot.aireview.diffview.ReviewSession
import com.yuriyvot.aireview.diffview.ReviewSource
import com.yuriyvot.aireview.editor.CommentPopups
import com.yuriyvot.aireview.git.Git
import com.yuriyvot.aireview.model.CommentStatus
import com.yuriyvot.aireview.model.ReviewComment
import com.yuriyvot.aireview.store.ClaudeLive
import com.yuriyvot.aireview.store.CommentStore
import com.yuriyvot.aireview.util.Async
import com.yuriyvot.aireview.util.ExplorerNav
import com.yuriyvot.aireview.util.ProjectPaths
import com.yuriyvot.aireview.util.RepoPaths
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.event.HierarchyEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.DefaultComboBoxModel
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class ReviewChangesPane(project: Project) : AbstractProjectViewPane(project) {

    private sealed interface Node {
        data class Dir(val name: String, val key: String, val count: Int) : Node
        data class FileNode(val item: FileItem, val flat: Boolean) : Node
    }

    private val session = ReviewSession.getInstance(project)
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val collapsed = HashSet<String>()
    private var component: JComponent? = null
    private var syncing = false
    private var updatingHeader = false
    private var shownKey: Any? = null
    private var openCounts: Map<String, Pair<Int, Boolean>> = emptyMap()
    private var badgeDigits = 1

    private val titleLabel = JBLabel().apply { font = JBFont.label().asBold() }
    private val relationLabel = JBLabel("относительно").apply { foreground = NamedColorUtil.getInactiveTextColor() }
    private val baseCombo = ComboBox<String>(DefaultComboBoxModel()).apply {
        isSwingPopup = false
        setMinimumAndPreferredWidth(JBUI.scale(170))
        toolTipText = "Ветка, относительно которой считаются изменения"
    }
    private val backLink = ActionLink("← к изменениям ветки") { session.backToBranch() }
    private val infoLabel = JBLabel().apply {
        foreground = NamedColorUtil.getInactiveTextColor()
        font = JBFont.small()
    }
    private val worktreeCheck = JBCheckBox("с незакоммиченным").apply {
        toolTipText = "Показывать незакоммиченные изменения и новые файлы"
        isOpaque = false
    }
    private val statsLabel = JBLabel().apply { foreground = NamedColorUtil.getInactiveTextColor() }

    override fun getTitle(): String = "AI Review"

    override fun getIcon(): Icon = AllIcons.Actions.Diff

    override fun getId(): String = ID

    override fun getWeight(): Int = 100

    override fun createSelectInTarget(): SelectInTarget = object : SelectInTarget {
        override fun canSelect(context: SelectInContext): Boolean = false

        override fun selectIn(context: SelectInContext, requestFocus: Boolean) {}

        override fun getToolWindowId(): String = ToolWindowId.PROJECT_VIEW

        override fun getMinorViewId(): String = ID

        override fun getWeight(): Float = 100f

        override fun toString(): String = "AI Review"
    }

    override fun createComponent(): JComponent {
        component?.let { return it }
        val tree = FilesTree().apply {
            isRootVisible = false
            showsRootHandles = true
            cellRenderer = Renderer()
        }
        myTree = tree
        ToolTipManager.sharedInstance().registerComponent(tree)
        TreeSpeedSearch.installOn(tree, true) { path ->
            when (val node = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
                is Node.FileNode -> node.item.name
                is Node.Dir -> node.name
                else -> ""
            }
        }
        tree.addTreeSelectionListener {
            if (syncing) return@addTreeSelectionListener
            val item = selectedItem() ?: return@addTreeSelectionListener
            session.open(item.path, permanent = false, focus = false)
        }
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                dirOf(event.path)?.let { collapsed.remove(it.key) }
            }

            override fun treeCollapsed(event: TreeExpansionEvent) {
                dirOf(event.path)?.let { collapsed.add(it.key) }
            }
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) {
                    val item = selectedItem() ?: return
                    session.open(item.path, permanent = true, focus = true)
                    e.consume()
                }
            }
        })
        DumbAwareAction.create {
            selectedItem()?.let { session.open(it.path, permanent = true, focus = true) }
        }.registerCustomShortcutSet(CustomShortcutSet(KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), null)), tree, this)
        PopupHandler.installPopupMenu(tree, popupGroup(), "AIReviewChangesPopup")

        baseCombo.addActionListener {
            if (updatingHeader) return@addActionListener
            val ref = baseCombo.selectedItem as? String ?: return@addActionListener
            if (ref != session.state?.resolved?.branch?.baseRef) session.setBase(ref)
        }
        worktreeCheck.addActionListener {
            if (!updatingHeader) session.setIncludeWorktree(worktreeCheck.isSelected)
        }

        val toolbarGroup = DefaultActionGroup(
            RefreshAction(), Separator.getInstance(), ViewModeAction(false), ViewModeAction(true), HideMetaAction(),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar("AIReviewChangesPane", toolbarGroup, true).apply {
            targetComponent = tree
            component.isOpaque = false
            component.border = JBUI.Borders.empty()
        }
        val titleRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(2))).apply {
                isOpaque = false
                add(titleLabel)
                add(relationLabel)
                add(baseCombo)
                add(backLink)
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(toolbar.component, BorderLayout.NORTH)
            }, BorderLayout.EAST)
        }
        val header = JPanel(VerticalLayout(JBUI.scale(3))).apply {
            border = JBUI.Borders.empty(4, 4, 6, 6)
            add(titleRow)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(infoLabel)
            })
            add(JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply {
                isOpaque = false
                add(worktreeCheck)
                add(statsLabel)
            })
        }

        myProject.messageBus.connect(this).subscribe(ReviewSession.TOPIC, ReviewSession.Listener { refresh() })
        ClaudeLive.getInstance(myProject).start()
        myProject.messageBus.connect(this).subscribe(ClaudeLive.TOPIC, ClaudeLive.Listener { updateHeader() })
        myProject.messageBus.connect(this).subscribe(CommentStore.TOPIC, CommentStore.Listener {
            recountComments()
            TreeUtil.invalidateCacheAndRepaint(tree.ui)
        })

        header.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (header.preferredSize.height != header.height) SwingUtilities.invokeLater { header.revalidate() }
            }
        })
        val panel = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        }
        panel.addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && panel.isShowing) {
                if (session.state == null) session.ensureLoaded() else session.refreshIfStale(3000)
            }
        }
        component = panel
        session.ensureLoaded()
        refresh()
        return panel
    }

    override fun getComponentToFocus(): JComponent? = myTree

    override fun updateFromRoot(restoreExpandedPaths: Boolean): ActionCallback {
        session.reload()
        return ActionCallback.DONE
    }

    override fun select(element: Any?, file: VirtualFile?, requestFocus: Boolean) {
        val path = (file as? ReviewFile)?.repoPath ?: return
        syncSelection(path)
    }

    override fun uiDataSnapshot(sink: DataSink) {
        super.uiDataSnapshot(sink)
        val vf = selectedItem()?.let { realFile(it.path) } ?: return
        sink[CommonDataKeys.VIRTUAL_FILE] = vf
        sink[CommonDataKeys.VIRTUAL_FILE_ARRAY] = arrayOf(vf)
    }

    // ------------------------------------------------------------------ state → UI

    private fun refresh() {
        val tree = myTree ?: return
        val state = session.state
        val items = FileTreeDir.visible(state?.items.orEmpty(), session.hideMeta)
        val flat = session.flat
        recountComments()
        val key = Triple(items, flat, state?.ends)
        if (key != shownKey) {
            shownKey = key
            rebuild(items, flat)
        }
        updateHeader()
        updateEmptyText()
        syncSelection(session.currentPath)
        TreeUtil.invalidateCacheAndRepaint(tree.ui)
    }

    private fun updateEmptyText() {
        val text = myTree?.emptyText ?: return
        text.clear()
        val error = session.error
        when {
            session.state == null && error != null -> {
                text.text = error
                text.appendLine("Повторить", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { session.reload() }
            }
            session.state == null -> text.text = "Загрузка изменений…"
            else -> text.text = "Изменений нет"
        }
    }

    private fun updateHeader() {
        updatingHeader = true
        try {
            val state = session.state
            val branchMode = session.source is ReviewSource.Branch
            relationLabel.isVisible = branchMode && state != null
            baseCombo.isVisible = branchMode && state != null
            worktreeCheck.isVisible = branchMode && state != null
            backLink.isVisible = !branchMode
            if (state == null) {
                titleLabel.text = if (session.error != null) "AI Review" else "Загрузка…"
                titleLabel.icon = AllIcons.Vcs.Branch
                infoLabel.text = ""
                statsLabel.text = ""
                return
            }
            val r = state.resolved
            val branch = r.branch
            if (branchMode && branch != null) {
                titleLabel.text = branch.current ?: "HEAD"
                titleLabel.icon = AllIcons.Vcs.Branch
                val comboModel = baseCombo.model as DefaultComboBoxModel<String>
                val currentItems = (0 until comboModel.size).map { comboModel.getElementAt(it) }
                if (currentItems != branch.candidates) {
                    comboModel.removeAllElements()
                    branch.candidates.forEach { comboModel.addElement(it) }
                }
                baseCombo.selectedItem = branch.baseRef
                worktreeCheck.isSelected = branch.includeWorktree
                val parts = mutableListOf<String>()
                if (branch.baseRef == null) parts += "базовая ветка не найдена"
                else r.baseInfo?.let { parts += "база ${it.short} · ${it.subject}" }
                branch.mergedVia?.let { parts += "уже влита: ${it.short} от ${it.date}" }
                infoLabel.text = parts.joinToString("   ·   ")
                infoLabel.toolTipText = r.baseInfo?.let { "${it.sha}\n${it.subject}\n${it.date}" }
            } else {
                titleLabel.text = "${r.baseInfo?.short ?: "пусто"} → ${r.targetInfo?.short ?: "рабочая копия"}"
                titleLabel.icon = AllIcons.Actions.Diff
                infoLabel.text = r.targetInfo?.subject ?: r.baseInfo?.subject ?: ""
                infoLabel.toolTipText = null
            }
            val added = state.items.sumOf { it.added ?: 0 }
            val deleted = state.items.sumOf { it.deleted ?: 0 }
            statsLabel.text = "${state.items.size} ${plural(state.items.size)}  +$added −$deleted" +
                (if (session.loading) "  ·  обновление…" else "") +
                (if (ClaudeLive.getInstance(myProject).alive) "  ·  Claude на связи" else "")
        } finally {
            updatingHeader = false
        }
    }

    private fun plural(n: Int): String {
        val a = n % 100
        val b = n % 10
        return when {
            a in 11..19 -> "файлов"
            b == 1 -> "файл"
            b in 2..4 -> "файла"
            else -> "файлов"
        }
    }

    private fun rebuild(items: List<FileItem>, flat: Boolean) {
        val tree = myTree ?: return
        syncing = true
        try {
            root.removeAllChildren()
            if (flat) {
                FileTreeDir.order(items, true).forEach { root.add(DefaultMutableTreeNode(Node.FileNode(it, true))) }
            } else {
                fill(root, FileTreeDir.build(items))
            }
            tree.showsRootHandles = !flat
            model.reload()
            expandAll(tree, root)
        } finally {
            syncing = false
        }
    }

    private fun fill(node: DefaultMutableTreeNode, dir: FileTreeDir) {
        dir.sortedDirs().forEach { d ->
            val child = DefaultMutableTreeNode(Node.Dir(d.name, d.key, d.count))
            node.add(child)
            fill(child, d)
        }
        dir.sortedFiles().forEach { node.add(DefaultMutableTreeNode(Node.FileNode(it, false))) }
    }

    private fun expandAll(tree: JTree, node: DefaultMutableTreeNode) {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            val dir = child.userObject as? Node.Dir ?: continue
            if (dir.key !in collapsed) {
                tree.expandPath(TreePath(child.path))
                expandAll(tree, child)
            }
        }
    }

    private fun syncSelection(path: String?) {
        val tree = myTree ?: return
        if (path == null || selectedItem()?.path == path) return
        val node = TreeUtil.treeNodeTraverser(root).filter(DefaultMutableTreeNode::class.java)
            .find { (it.userObject as? Node.FileNode)?.item?.path == path } ?: return
        syncing = true
        try {
            val treePath = TreePath(node.path)
            tree.selectionPath = treePath
            tree.scrollPathToVisible(treePath)
        } finally {
            syncing = false
        }
    }

    private fun commentsOf(item: FileItem): Pair<Int, Boolean>? =
        openCounts[item.projectPath] ?: item.projectOldPath?.let { openCounts[it] }

    private fun recountComments() {
        val counts = HashMap<String, Pair<Int, Boolean>>()
        CommentStore.getInstance(myProject).comments().filter { it.status.isOpen && it.filePath != null }.forEach { c ->
            val path = c.filePath!!
            val prev = counts[path]
            counts[path] = (prev?.first ?: 0) + 1 to ((prev?.second ?: false) || c.status == CommentStatus.HAS_QUESTIONS)
        }
        openCounts = counts
        badgeDigits = (counts.values.maxOfOrNull { it.first } ?: 1).coerceAtMost(99).toString().length
    }

    // ------------------------------------------------------------------ selection helpers

    private fun selectedNode(): Node? = (myTree?.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Node

    private fun selectedItem(): FileItem? = (selectedNode() as? Node.FileNode)?.item

    private fun dirOf(path: TreePath): Node.Dir? = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Node.Dir

    private fun realFile(repoPath: String): VirtualFile? {
        val root = session.repoRoot ?: return null
        return LocalFileSystem.getInstance().findFileByIoFile(File(root, repoPath))
    }

    private fun showInFolder(repoPath: String) {
        val root = session.repoRoot ?: return
        ExplorerNav.showInFolder(myProject, File(root, repoPath))
    }

    private fun commentFolder(dir: Node.Dir) {
        val tree = myTree ?: return
        val root = session.repoRoot ?: return
        val projectPath = RepoPaths(root, myProject).toProject(dir.key) ?: dir.key
        val popup = CommentPopups.input(myProject, "Комментарий для Claude к папке", "$projectPath/") { text, _ ->
            Async.background {
                val head = Git(root).lineOrNull("rev-parse", "HEAD")
                CommentStore.getInstance(myProject).add(ReviewComment(filePath = projectPath, comment = text, commitHash = head))
            }
        }
        val bounds = tree.selectionPath?.let { tree.getPathBounds(it) }
        if (bounds != null) popup.show(RelativePoint(tree, Point(bounds.x + JBUI.scale(16), bounds.y + bounds.height)))
        else popup.showInFocusCenter()
    }

    // ------------------------------------------------------------------ actions

    private fun popupGroup(): DefaultActionGroup = DefaultActionGroup().apply {
        add(object : DumbAwareAction("Комментарий к файлу…", null, AllIcons.General.Balloon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = selectedItem() != null
            }

            override fun actionPerformed(e: AnActionEvent) {
                selectedItem()?.let { session.openForFileComment(it.path) }
            }
        })
        add(object : DumbAwareAction("Комментарий к папке…", null, AllIcons.General.Balloon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = selectedNode() is Node.Dir
            }

            override fun actionPerformed(e: AnActionEvent) {
                (selectedNode() as? Node.Dir)?.let { commentFolder(it) }
            }
        })
        add(object : DumbAwareAction("Открыть в редакторе", null, AllIcons.Actions.EditSource) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = selectedItem()?.let { realFile(it.path) } != null
            }

            override fun actionPerformed(e: AnActionEvent) {
                val vf = selectedItem()?.let { realFile(it.path) } ?: return
                OpenFileDescriptor(myProject, vf).navigate(true)
            }
        })
        add(object : DumbAwareAction("Показать в папке", "Открыть место в обычном дереве (Unity или File System)", AllIcons.Actions.MenuOpen) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = selectedNode() != null
            }

            override fun actionPerformed(e: AnActionEvent) {
                when (val node = selectedNode()) {
                    is Node.FileNode -> showInFolder(node.item.path)
                    is Node.Dir -> showInFolder(node.key)
                    else -> {}
                }
            }
        })
        add(Separator.getInstance())
        add(ViewModeAction(false))
        add(ViewModeAction(true))
        add(HideMetaAction())
        add(RefreshAction())
    }

    private inner class ViewModeAction(private val flatMode: Boolean) : ToggleAction(
        if (flatMode) "Плоским списком" else "Деревом",
        if (flatMode) "Файлы списком: имя, затем путь" else "Файлы деревом папок",
        if (flatMode) AllIcons.Actions.ListFiles else AllIcons.Actions.ShowAsTree,
    ), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent): Boolean = session.flat == flatMode
        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) session.setFlat(flatMode)
        }
    }

    private inner class HideMetaAction :
        ToggleAction("Скрывать .meta", "Не показывать .meta-файлы Unity", AllIcons.General.Filter), DumbAware {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun isSelected(e: AnActionEvent): Boolean = session.hideMeta
        override fun setSelected(e: AnActionEvent, state: Boolean) = session.setHideMeta(state)
    }

    private inner class RefreshAction : DumbAwareAction("Обновить", "Перечитать изменения из git", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !session.loading
        }

        override fun actionPerformed(e: AnActionEvent) = session.reload()
    }

    // ------------------------------------------------------------------ rendering

    private fun shownDir(item: FileItem): String {
        val shown = item.projectPath?.takeUnless { it.startsWith("../") } ?: item.path
        return shown.substringBeforeLast('/', "")
    }

    /** In the flat mode the folder of every file is painted at the right edge, cut from the start when it does not fit. */
    private inner class FilesTree : DnDAwareTree(model) {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val flat = session.flat
            val g2 = g.create() as Graphics2D
            try {
                UISettings.setupAntialiasing(g2)
                g2.font = font
                val fm = g2.fontMetrics
                val visible = visibleRect
                val right = visible.x + visible.width - JBUI.scale(20)
                val first = getClosestRowForLocation(visible.x, visible.y)
                val last = getClosestRowForLocation(visible.x, visible.y + visible.height)
                if (first < 0 || last < 0) return
                for (row in first..last) {
                    val node = (getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Node.FileNode ?: continue
                    if (!flat) {
                        val counts = commentsOf(node.item) ?: continue
                        val bounds = getRowBounds(row) ?: continue
                        val badge = CountBadge(counts.first, if (counts.second) QUESTION else PENDING, minOf(counts.first, 99).toString().length)
                        val x = maxOf(bounds.x + bounds.width + JBUI.scale(8), right - badge.iconWidth)
                        badge.paintIcon(this, g2, x, bounds.y + (bounds.height - badge.iconHeight) / 2)
                        continue
                    }
                    val dir = shownDir(node.item)
                    if (dir.isEmpty()) continue
                    val bounds = getRowBounds(row) ?: continue
                    val left = bounds.x + bounds.width + JBUI.scale(12)
                    val available = right - left
                    if (available < JBUI.scale(30)) continue
                    val text = fitFromStart(dir, fm, available)
                    val selectedFocused = isRowSelected(row) && hasFocus()
                    g2.color = if (selectedFocused) UIUtil.getTreeSelectionForeground(true) else NamedColorUtil.getInactiveTextColor()
                    g2.drawString(text, right - fm.stringWidth(text), bounds.y + (bounds.height - fm.height) / 2 + fm.ascent)
                }
            } finally {
                g2.dispose()
            }
        }

        override fun getToolTipText(event: MouseEvent): String? {
            val row = getClosestRowForLocation(event.x, event.y)
            val bounds = if (row >= 0) getRowBounds(row) else null
            if (bounds != null && event.y >= bounds.y && event.y < bounds.y + bounds.height) {
                val node = (getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode)?.userObject
                if (node is Node.FileNode) {
                    val item = node.item
                    return item.path + (item.oldPath?.takeIf { it != item.path }?.let { "\nбыло: $it" } ?: "")
                }
            }
            return super.getToolTipText(event)
        }

        private fun fitFromStart(text: String, fm: FontMetrics, width: Int): String {
            if (fm.stringWidth(text) <= width) return text
            val ellipsis = "…"
            var start = 0
            while (start < text.length && fm.stringWidth(ellipsis + text.substring(start)) > width) start++
            return ellipsis + text.substring(start)
        }
    }

    private inner class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            when (val node = (value as? DefaultMutableTreeNode)?.userObject) {
                is Node.Dir -> {
                    icon = AllIcons.Nodes.Folder
                    append(node.name)
                    append("  ${node.count}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                is Node.FileNode -> renderFile(node.item, node.flat, node.flat && siblingsHaveComments(value as DefaultMutableTreeNode))
                else -> {}
            }
        }

        private fun siblingsHaveComments(node: DefaultMutableTreeNode): Boolean {
            val parent = node.parent ?: return false
            for (i in 0 until parent.childCount) {
                val item = ((parent.getChildAt(i) as? DefaultMutableTreeNode)?.userObject as? Node.FileNode)?.item ?: continue
                if (commentsOf(item) != null) return true
            }
            return false
        }

        private fun renderFile(item: FileItem, flat: Boolean, reserveBadge: Boolean) {
            val counts = commentsOf(item)
            val fileIcon = FileTypeManager.getInstance().getFileTypeByFileName(item.name).icon
            icon = if (reserveBadge) RowIcon(CountBadge(counts?.first, if (counts?.second == true) QUESTION else PENDING, badgeDigits), fileIcon) else fileIcon
            iconTextGap = JBUI.scale(4)
            var style = SimpleTextAttributes.STYLE_PLAIN
            if (item.status == "D") style = style or SimpleTextAttributes.STYLE_STRIKEOUT
            append(item.name, SimpleTextAttributes(style, statusColor(item.status)))
            when {
                item.status == "?" -> append("  new", ADD_SMALL)
                item.binary -> append("  bin", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                else -> {
                    if ((item.added ?: 0) > 0) append("  +${item.added}", ADD_SMALL)
                    if ((item.deleted ?: 0) > 0) append(" −${item.deleted}", DEL_SMALL)
                }
            }
            toolTipText = item.path + (item.oldPath?.takeIf { it != item.path }?.let { "\nбыло: $it" } ?: "")
        }

        private fun statusColor(status: String) = when (status) {
            "A" -> FileStatus.ADDED.color
            "D" -> FileStatus.DELETED.color
            "R", "C", "M", "T" -> FileStatus.MODIFIED.color
            "?" -> FileStatus.UNKNOWN.color
            "U" -> FileStatus.MERGED_WITH_CONFLICTS.color
            else -> null
        } ?: UIUtil.getTreeForeground()
    }

    /** A round badge with the number of open comments, drawn before the file icon. */
    /** "N ●" before the file icon: the number of open comments and a dot. Empty (but as wide) when there are none. */
    private class CountBadge(private val count: Int?, private val color: Color, digits: Int) : Icon {
        private val font = JBFont.small().asBold()
        private val metrics = JLabel().getFontMetrics(font)
        private val textWidth = metrics.stringWidth("9".repeat(digits))
        private val dot = JBUI.scale(8)
        private val gapBeforeDot = JBUI.scale(3)
        private val gapAfterDot = JBUI.scale(6)
        private val padLeft = JBUI.scale(4)

        override fun getIconWidth(): Int = padLeft + textWidth + gapBeforeDot + dot + gapAfterDot

        override fun getIconHeight(): Int = JBUI.scale(16)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val n = count ?: return
            val g2 = g.create() as Graphics2D
            try {
                GraphicsUtil.setupAAPainting(g2)
                UISettings.setupAntialiasing(g2)
                g2.color = color
                g2.font = font
                val text = if (n > 99) "99" else n.toString()
                val baseline = y + (iconHeight - metrics.height) / 2 + metrics.ascent
                g2.drawString(text, x + padLeft + textWidth - metrics.stringWidth(text), baseline)
                g2.fillOval(x + padLeft + textWidth + gapBeforeDot, y + (iconHeight - dot) / 2, dot, dot)
            } finally {
                g2.dispose()
            }
        }
    }

    companion object {
        const val ID = "AIReviewChanges"
        private val PENDING = JBColor(0x3574F0, 0x548AF7)
        private val QUESTION = JBColor(0xC77D00, 0xE8A33D)
        private val ADD_SMALL = SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, JBColor(0x1A7F37, 0x57AB5A))
        private val DEL_SMALL = SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, JBColor(0xCF222E, 0xE5534B))
    }
}
