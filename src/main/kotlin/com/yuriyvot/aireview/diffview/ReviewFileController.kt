package com.yuriyvot.aireview.diffview

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.yuriyvot.aireview.comments.CommentOps
import com.yuriyvot.aireview.comments.int
import com.yuriyvot.aireview.comments.str
import com.yuriyvot.aireview.git.CommitInfo
import com.yuriyvot.aireview.git.DiffEnds
import com.yuriyvot.aireview.git.Git
import com.yuriyvot.aireview.model.ReviewComment
import com.yuriyvot.aireview.store.CommentStore
import com.yuriyvot.aireview.util.Async
import com.yuriyvot.aireview.util.RepoPaths
import com.yuriyvot.aireview.web.Reply
import com.yuriyvot.aireview.web.WebPanel
import java.awt.datatransfer.StringSelection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JComponent

class ReviewFileController(private val project: Project, val file: ReviewFile, parent: Disposable) {
    private val session = ReviewSession.getInstance(project)
    private val store = CommentStore.getInstance(project)
    private val git = Git(file.repoRoot)
    private val paths = RepoPaths(file.repoRoot, project)

    @Volatile
    private var shownEnds: DiffEnds? = null

    @Volatile
    private var shownKey: String? = null

    private val panel = WebPanel(parent, "file", ::boot, ::handle)

    val component: JComponent get() = panel.component

    init {
        file.controller = this
        Disposer.register(panel) { if (file.controller === this) file.controller = null }
        val bus = project.messageBus.connect(panel)
        bus.subscribe(CommentStore.TOPIC, CommentStore.Listener { panel.emit("comments", CommentOps.commentsJson(project)) })
        bus.subscribe(ReviewSession.TOPIC, ReviewSession.Listener { onSessionChanged() })
    }

    fun focusComment(id: String) = panel.emit("focusComment", JsonObject().apply { addProperty("id", id) })

    fun commentFile() = panel.emit("fileComment", JsonObject())

    fun retargeted() {
        shownEnds = null
        panel.emit("stale", JsonObject().apply { addProperty("retarget", true) })
    }

    fun pushPrefs(prefs: JsonObject) = panel.emit("prefs", prefs)

    private fun onSessionChanged() {
        if (shownEnds == null) return
        val st = session.state ?: return
        val item = st.item(file.repoPath)
        if (st.ends != shownEnds || item?.contentKey != shownKey) {
            shownEnds = null
            panel.emit("stale", JsonObject())
        }
    }

    private fun boot(): JsonObject = JsonObject().apply { add("prefs", DiffPrefs.load()) }

    private fun handle(method: String, p: JsonObject, reply: Reply) {
        if (CommentOps.handle(project, method, p, reply)) return
        when (method) {
            "init" -> reply.async {
                awaitState()
                initJson()
            }
            "file" -> reply.async { fileJson(p.bool("ignoreWs"), p.bool("force")) }
            "addComment" -> reply.async {
                addComment(p)
                Async.edt(project) { session.pin(file) }
                JsonNull.INSTANCE
            }
            "updateRange" -> reply.async {
                updateRange(p)
                JsonNull.INSTANCE
            }
            "savePrefs" -> {
                DiffPrefs.merge(p)
                Async.edt(project) {
                    FileEditorManager.getInstance(project).openFiles.filterIsInstance<ReviewFile>()
                        .mapNotNull { it.controller }.filter { it !== this }.forEach { it.pushPrefs(p) }
                }
                reply.ok()
            }
            "step" -> {
                val dir = p.int("dir") ?: 1
                Async.edt(project) { session.step(file.repoPath, dir) }
                reply.ok()
            }
            "pin" -> {
                Async.edt(project) { session.pin(file) }
                reply.ok()
            }
            "reload" -> {
                session.reload()
                reply.ok()
            }
            "openInEditor" -> {
                openInEditor(p.int("line"))
                reply.ok()
            }
            "copy" -> {
                CopyPasteManager.getInstance().setContents(StringSelection(p.str("text") ?: ""))
                reply.ok()
            }
            else -> reply.fail("Неизвестный метод: $method")
        }
    }

    private fun awaitState() {
        if (session.state != null && !session.loading) return
        val latch = CountDownLatch(1)
        session.whenLoaded { latch.countDown() }
        latch.await(90, TimeUnit.SECONDS)
    }

    private fun initJson(): JsonObject {
        val st = session.state
        val item = st?.item(file.repoPath)
        shownEnds = st?.ends
        shownKey = item?.contentKey
        val order = session.visibleOrder()
        return JsonObject().apply {
            addProperty("path", file.repoPath)
            addProperty("error", session.error)
            add("file", item?.let(::itemJson) ?: JsonNull.INSTANCE)
            add("ends", st?.ends?.let { e ->
                JsonObject().apply {
                    addProperty("base", e.base)
                    addProperty("target", e.target)
                    addProperty("id", e.rangeId)
                }
            } ?: JsonNull.INSTANCE)
            add("base", st?.resolved?.baseInfo?.let(::commitJson) ?: JsonNull.INSTANCE)
            add("target", st?.resolved?.targetInfo?.let(::commitJson) ?: JsonNull.INSTANCE)
            addProperty("index", order.indexOfFirst { it.path == file.repoPath })
            addProperty("total", order.size)
            add("comments", CommentOps.commentsJson(project))
            addProperty("focus", file.pendingFocus.also { file.pendingFocus = null })
            addProperty("fileComment", file.pendingFileComment.also { file.pendingFileComment = false })
        }
    }

    private fun itemJson(f: FileItem): JsonObject = JsonObject().apply {
        addProperty("status", f.status)
        addProperty("path", f.path)
        addProperty("oldPath", f.oldPath)
        addProperty("added", f.added)
        addProperty("deleted", f.deleted)
        addProperty("binary", f.binary)
        addProperty("projectPath", f.projectPath)
        addProperty("projectOldPath", f.projectOldPath)
    }

    private fun commitJson(c: CommitInfo): JsonObject = JsonObject().apply {
        addProperty("sha", c.sha)
        addProperty("short", c.short)
        addProperty("subject", c.subject)
        addProperty("date", c.date)
    }

    private fun fileJson(ignoreWs: Boolean, force: Boolean): JsonElement {
        val st = session.state ?: error("Изменения ещё не загружены")
        val changed = st.resolved.files.firstOrNull { it.path == file.repoPath } ?: error("Файла нет в текущем сравнении")
        return FileDiffBuilder.build(git, st.ends, changed, ignoreWs, force).apply {
            addProperty("path", changed.path)
            addProperty("ignoreWs", ignoreWs)
        }
    }

    private fun addComment(p: JsonObject) {
        val st = session.state ?: error("Изменения ещё не загружены")
        val text = p.str("text")?.trim().orEmpty()
        if (text.isEmpty()) error("Пустой комментарий")
        val repoPath = p.str("path") ?: file.repoPath
        val oldSide = p.str("side") == "o"
        val start = p.int("startLine")
        store.add(
            ReviewComment(
                filePath = paths.toProject(repoPath) ?: repoPath,
                startLine = start,
                endLine = if (start == null) null else (p.int("endLine") ?: start),
                selectedText = p.str("selectedText"),
                comment = text,
                commitHash = git.lineOrNull("rev-parse", "HEAD"),
                revision = if (oldSide) st.ends.base else st.ends.target,
                diffRange = st.ends.rangeId,
                diffSnippet = p.str("diffSnippet"),
            )
        )
    }

    private fun updateRange(p: JsonObject) {
        val st = session.state ?: error("Изменения ещё не загружены")
        val id = p.str("id") ?: error("Нет комментария")
        val start = p.int("startLine") ?: error("Нет строк")
        val end = p.int("endLine") ?: start
        val repoPath = p.str("path") ?: file.repoPath
        val oldSide = p.str("side") == "o"
        val text = p.str("text")?.trim()?.takeIf { it.isNotEmpty() }
        store.update(id) {
            it.copy(
                comment = text ?: it.comment,
                filePath = paths.toProject(repoPath) ?: repoPath,
                startLine = minOf(start, end),
                endLine = maxOf(start, end),
                selectedText = p.str("selectedText") ?: it.selectedText,
                diffSnippet = p.str("diffSnippet"),
                revision = if (oldSide) st.ends.base else st.ends.target,
                diffRange = st.ends.rangeId,
            )
        }
    }

    private fun openInEditor(line: Int?) {
        val abs = paths.absolute(file.repoPath)
        Async.background {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(abs) ?: return@background
            Async.edt(project) {
                OpenFileDescriptor(project, vf, ((line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
            }
        }
    }

    private fun JsonObject.bool(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
}
