package com.yuriyvot.aireview.diffview

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.fileTypes.ex.FakeFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import java.beans.PropertyChangeListener
import java.io.File
import javax.swing.Icon
import javax.swing.JComponent

object ReviewFileType : FakeFileType() {
    override fun getName(): String = "AI Review Diff"

    override fun getDescription(): String = "AI Review diff"

    override fun getIcon(): Icon = AllIcons.Actions.Diff

    override fun isMyFileType(file: VirtualFile): Boolean = file is ReviewFile
}

/** A diff tab of one changed file. [repoPath] is relative to the repository root; the preview tab changes it. */
class ReviewFile(val repoRoot: File, path: String) : LightVirtualFile(path.substringAfterLast('/'), ReviewFileType, "") {

    @Volatile
    var repoPath: String = path

    @Volatile
    var controller: ReviewFileController? = null

    @Volatile
    var pendingFocus: String? = null

    @Volatile
    var pendingFileComment = false

    @Volatile
    var pinned = false

    init {
        isWritable = false
    }
}

class ReviewFileTabTitleProvider : EditorTabTitleProvider, DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? =
        (file as? ReviewFile)?.repoPath?.substringAfterLast('/')

    override fun getEditorTabTooltipText(project: Project, file: VirtualFile): String? =
        (file as? ReviewFile)?.let { "Изменения: ${it.repoPath}" }
}

class ReviewFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is ReviewFile

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = ReviewFileEditor(project, file as ReviewFile)

    override fun getEditorTypeId(): String = "ai-review-file-diff"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class ReviewFileEditor(project: Project, private val file: ReviewFile) : UserDataHolderBase(), FileEditor {
    private val controller = ReviewFileController(project, file, this)

    override fun getComponent(): JComponent = controller.component

    override fun getPreferredFocusedComponent(): JComponent = controller.component

    override fun getName(): String = "AI Review"

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getFile(): VirtualFile = file

    override fun dispose() {}
}
