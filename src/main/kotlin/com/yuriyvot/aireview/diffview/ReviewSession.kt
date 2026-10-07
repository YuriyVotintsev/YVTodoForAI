package com.yuriyvot.aireview.diffview

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.messages.Topic
import com.yuriyvot.aireview.git.ChangedFile
import com.yuriyvot.aireview.git.DiffEnds
import com.yuriyvot.aireview.git.Git
import com.yuriyvot.aireview.git.GitDiff
import com.yuriyvot.aireview.git.GitException
import com.yuriyvot.aireview.git.ResolvedDiff
import com.yuriyvot.aireview.model.ReviewComment
import com.yuriyvot.aireview.pane.ReviewChangesPane
import com.yuriyvot.aireview.util.Async
import com.yuriyvot.aireview.util.Notify
import com.yuriyvot.aireview.util.ProjectPaths
import com.yuriyvot.aireview.util.RepoPaths
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed interface ReviewSource {
    data class Branch(val includeWorktree: Boolean) : ReviewSource
    data class Range(val ends: DiffEnds) : ReviewSource
}

class ReviewState(val source: ReviewSource, val resolved: ResolvedDiff, val items: List<FileItem>) {
    val ends: DiffEnds get() = resolved.ends

    fun item(path: String): FileItem? = items.firstOrNull { it.path == path }
}

@Service(Service.Level.PROJECT)
class ReviewSession(private val project: Project) : Disposable {

    fun interface Listener {
        fun sessionChanged()
    }

    @Volatile
    var source: ReviewSource = ReviewSource.Branch(DiffPrefs.bool("includeWorktree", true))
        private set

    @Volatile
    var state: ReviewState? = null
        private set

    @Volatile
    var error: String? = null
        private set

    @Volatile
    var loading = false
        private set

    @Volatile
    var currentPath: String? = null
        private set

    @Volatile
    var repoRoot: File? = null
        private set

    val hideMeta: Boolean get() = DiffPrefs.bool("hideMeta", true)

    val flat: Boolean get() = DiffPrefs.bool("flat", false)

    private val loadSeq = AtomicInteger()

    @Volatile
    private var loadedAt = 0L

    private val files = ConcurrentHashMap<String, ReviewFile>()
    private var preview: ReviewFile? = null
    private val waiters = ArrayList<() -> Unit>()

    init {
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                val file = event.newFile as? ReviewFile ?: return
                if (currentPath != file.repoPath) {
                    currentPath = file.repoPath
                    changed()
                }
                refreshIfStale(5000)
            }

            override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                if (file === preview) preview = null
                if (file is ReviewFile && files[file.repoPath] === file) files.remove(file.repoPath)
            }
        })
    }

    // ------------------------------------------------------------------ loading

    fun ensureLoaded() {
        if (state == null && !loading && error == null) reload()
    }

    fun refreshIfStale(maxAgeMs: Long = 3000) {
        if (!loading && System.currentTimeMillis() - loadedAt > maxAgeMs) reload()
    }

    fun reload() {
        val seq = loadSeq.incrementAndGet()
        loading = true
        changed()
        Async.background {
            var newState: ReviewState? = null
            var newError: String? = null
            try {
                val root = repoRoot ?: findRoot(project) ?: throw GitException("Проект не в git-репозитории")
                repoRoot = root
                val git = Git(root)
                val src = source
                val resolved = when (src) {
                    is ReviewSource.Branch ->
                        GitDiff.resolveBranch(git, DiffPrefs.baseFor(project, GitDiff.currentBranch(git)), src.includeWorktree)
                    is ReviewSource.Range -> GitDiff.resolveRange(git, src.ends)
                }
                val paths = RepoPaths(root, project)
                newState = ReviewState(src, resolved, resolved.files.map { toItem(root, paths, resolved, it) })
            } catch (e: Exception) {
                newError = e.message ?: e.javaClass.simpleName
            }
            if (seq != loadSeq.get()) return@background
            if (newState != null) state = newState
            error = newError
            loading = false
            loadedAt = System.currentTimeMillis()
            val callbacks = synchronized(waiters) { waiters.toList().also { waiters.clear() } }
            callbacks.forEach { it() }
            changed()
        }
    }

    fun whenLoaded(action: () -> Unit) {
        val runNow = synchronized(waiters) {
            if (!loading && (state != null || error != null)) true else {
                waiters += action
                false
            }
        }
        if (runNow) action()
        ensureLoaded()
    }

    fun setBase(ref: String) {
        DiffPrefs.saveBase(project, state?.resolved?.branch?.current, ref)
        reload()
    }

    fun setIncludeWorktree(value: Boolean) {
        DiffPrefs.setBool("includeWorktree", value)
        source = ReviewSource.Branch(value)
        reload()
    }

    fun showRange(ends: DiffEnds) {
        if (state?.ends == ends && source is ReviewSource.Range) return
        source = ReviewSource.Range(ends)
        reload()
    }

    fun backToBranch() {
        source = ReviewSource.Branch(DiffPrefs.bool("includeWorktree", true))
        reload()
    }

    // ------------------------------------------------------------------ files

    fun setHideMeta(value: Boolean) {
        DiffPrefs.setBool("hideMeta", value)
        changed()
    }

    fun setFlat(value: Boolean) {
        DiffPrefs.setBool("flat", value)
        changed()
    }

    fun visibleOrder(): List<FileItem> =
        FileTreeDir.order(FileTreeDir.visible(state?.items.orEmpty(), hideMeta), flat)

    fun fileFor(path: String): ReviewFile? {
        val manager = FileEditorManager.getInstance(project)
        files[path]?.takeIf { manager.isFileOpen(it) }?.let { return it }
        return preview?.takeIf { it.repoPath == path && manager.isFileOpen(it) }
    }

    /**
     * Opens the diff tab of [path]. A non-permanent open reuses a single preview tab (like a single click in the
     * project tree); a permanent one (double click, Enter, a comment written in the tab) keeps its own tab.
     */
    fun open(path: String, permanent: Boolean, focus: Boolean) {
        val root = repoRoot ?: return
        val manager = FileEditorManager.getInstance(project)
        currentPath = path
        val pinned = files[path]?.takeIf { manager.isFileOpen(it) }
        val current = preview?.takeIf { manager.isFileOpen(it) }
        when {
            pinned != null -> manager.openFile(pinned, focus)
            permanent && current != null && current.repoPath == path -> {
                pin(current)
                manager.openFile(current, focus)
            }
            permanent -> {
                val file = ReviewFile(root, path).also { it.pinned = true }
                files[path] = file
                manager.openFile(file, focus)
            }
            current != null -> {
                if (current.repoPath != path) {
                    current.repoPath = path
                    FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(current)
                    current.controller?.retargeted()
                }
                manager.openFile(current, focus)
            }
            else -> {
                val file = ReviewFile(root, path)
                preview = file
                manager.openFile(file, focus)
            }
        }
        changed()
    }

    fun pin(file: ReviewFile) {
        file.pinned = true
        if (preview === file) {
            preview = null
            files[file.repoPath] = file
        }
    }

    fun openForFileComment(path: String) {
        open(path, permanent = true, focus = true)
        val file = fileFor(path) ?: return
        val controller = file.controller
        if (controller != null) controller.commentFile() else file.pendingFileComment = true
    }

    fun step(fromPath: String, dir: Int) {
        val order = visibleOrder()
        val index = order.indexOfFirst { it.path == fromPath }
        val next = when {
            index < 0 -> order.firstOrNull()
            else -> order.getOrNull(index + dir)
        } ?: return
        open(next.path, permanent = false, focus = true)
    }

    /**
     * Opens the diff tab of the file the comment belongs to and scrolls to the comment. When the file is not changed in
     * the current comparison, the ordinary editor is opened at the comment's line instead.
     */
    fun openComment(comment: ReviewComment) {
        val ends = comment.diffRange?.let { DiffEnds.parse(it) }
        if (ends != null && comment.revision != null && state?.ends != ends) showRange(ends)
        whenLoaded {
            Async.edt(project) {
                val root = repoRoot ?: findRoot(project) ?: return@edt
                val filePath = comment.filePath ?: return@edt
                val repoPath = RepoPaths(root, project).toRepo(filePath)
                val item = repoPath?.let { p -> state?.items?.firstOrNull { it.path == p || it.oldPath == p } }
                if (item == null) {
                    openInEditor(filePath, comment.startLine)
                    return@edt
                }
                open(item.path, permanent = true, focus = true)
                val file = fileFor(item.path) ?: return@edt
                val controller = file.controller
                if (controller != null) controller.focusComment(comment.id) else file.pendingFocus = comment.id
            }
        }
    }

    private fun openInEditor(projectPath: String, line: Int?) {
        Async.background {
            val vf = ProjectPaths.findFile(project, projectPath)
            Async.edt(project) {
                if (vf == null) Notify.warn(project, "Не найден файл: $projectPath")
                else OpenFileDescriptor(project, vf, ((line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
            }
        }
    }

    fun showPane() {
        Async.edt(project) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW) ?: return@edt
            toolWindow.show {
                ProjectView.getInstance(project).changeView(ReviewChangesPane.ID)
            }
        }
    }

    fun changed() {
        Async.edt(project, anyModality = true) { project.messageBus.syncPublisher(TOPIC).sessionChanged() }
    }

    private fun toItem(root: File, paths: RepoPaths, resolved: ResolvedDiff, f: ChangedFile) = FileItem(
        path = f.path,
        oldPath = f.oldPath,
        status = f.status,
        added = f.added,
        deleted = f.deleted,
        binary = f.binary,
        contentKey = contentKey(root, resolved, f),
        projectPath = paths.toProject(f.path),
        projectOldPath = f.oldPath?.let { paths.toProject(it) },
    )

    private fun contentKey(root: File, r: ResolvedDiff, f: ChangedFile): String {
        val newPart = f.newBlob ?: if (r.ends.target == null) {
            val onDisk = File(root, f.path)
            if (onDisk.isFile) "${onDisk.length()}:${onDisk.lastModified()}" else "-"
        } else {
            "-"
        }
        val digest = MessageDigest.getInstance("SHA-1").digest("${f.oldPath}|${f.path}|${f.oldBlob}|$newPart".toByteArray())
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }

    override fun dispose() {}

    companion object {
        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("AI Review session", Listener::class.java)

        fun getInstance(project: Project): ReviewSession = project.service()

        fun findRoot(project: Project): File? = ProjectPaths.base(project)?.let { Git.findRoot(it.toFile()) }
    }
}
