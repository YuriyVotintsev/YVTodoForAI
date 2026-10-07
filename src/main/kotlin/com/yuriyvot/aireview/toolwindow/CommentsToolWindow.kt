package com.yuriyvot.aireview.toolwindow

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.yuriyvot.aireview.comments.CommentOps
import com.yuriyvot.aireview.comments.str
import com.yuriyvot.aireview.diffview.ReviewSession
import com.yuriyvot.aireview.git.Git
import com.yuriyvot.aireview.model.ReviewComment
import com.yuriyvot.aireview.store.ClaudeLive
import com.yuriyvot.aireview.store.CommentStore
import com.yuriyvot.aireview.util.Async
import com.yuriyvot.aireview.web.Reply
import com.yuriyvot.aireview.web.WebPanel
import javax.swing.JComponent

const val TOOL_WINDOW_ID = "AI Review"

class CommentsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val controller = CommentsListController(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(controller.component, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

@Service(Service.Level.PROJECT)
class ReviewUi(private val project: Project) {
    @Volatile
    var list: CommentsListController? = null

    @Volatile
    var pendingFocus: String? = null

    fun showInList(commentId: String) {
        pendingFocus = commentId
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.activate {
            val controller = list
            if (controller != null && controller.loaded) {
                pendingFocus = null
                controller.focus(commentId)
            }
        }
    }

    fun takePendingFocus(): String? = pendingFocus.also { pendingFocus = null }

    companion object {
        fun getInstance(project: Project): ReviewUi = project.service()
    }
}

class CommentsListController(private val project: Project, parent: Disposable) {
    private val store = CommentStore.getInstance(project)
    private val panel = WebPanel(parent, "list", ::boot, ::handle)

    @Volatile
    var loaded = false
        private set

    val component: JComponent get() = panel.component

    init {
        val ui = ReviewUi.getInstance(project)
        ui.list = this
        Disposer.register(panel) { if (ui.list === this) ui.list = null }
        project.messageBus.connect(panel).subscribe(CommentStore.TOPIC, CommentStore.Listener {
            panel.emit("comments", state())
        })
        ClaudeLive.getInstance(project).start()
        project.messageBus.connect(panel).subscribe(ClaudeLive.TOPIC, ClaudeLive.Listener { alive ->
            panel.emit("live", JsonObject().apply { addProperty("alive", alive) })
        })
    }

    fun focus(id: String) = panel.emit("focusComment", JsonObject().apply { addProperty("id", id) })

    private fun boot(): JsonObject = JsonObject().apply {
        addProperty("shortcut", KeymapUtil.getFirstKeyboardShortcutText("AIReview.AddComment"))
        addProperty("filter", PropertiesComponent.getInstance().getValue(FILTER_KEY, "open"))
        addProperty("snip", PropertiesComponent.getInstance().getValue(SNIP_KEY, "short"))
    }

    private fun state(): JsonObject = JsonObject().apply {
        add("comments", CommentOps.commentsJson(project))
        addProperty("error", store.loadError)
        addProperty("live", ClaudeLive.getInstance(project).alive)
    }

    private fun handle(method: String, p: JsonObject, reply: Reply) {
        if (CommentOps.handle(project, method, p, reply)) return
        when (method) {
            "init" -> {
                loaded = true
                reply.ok(state().apply { addProperty("focus", ReviewUi.getInstance(project).takePendingFocus()) })
            }
            "addGeneral" -> {
                val text = p.str("text")?.trim().orEmpty()
                if (text.isEmpty()) return reply.fail("Пустой комментарий")
                reply.async {
                    val head = ReviewSession.findRoot(project)?.let { Git(it).lineOrNull("rev-parse", "HEAD") }
                    store.add(ReviewComment(comment = text, commitHash = head))
                    JsonNull.INSTANCE
                }
            }
            "clearClosed" -> reply.async {
                store.removeClosed()
                JsonNull.INSTANCE
            }
            "closeDone" -> reply.async {
                store.closeDone()
                JsonNull.INSTANCE
            }
            "openJson" -> {
                val path = store.file ?: return reply.fail("Нет пути проекта")
                Async.background {
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return@background
                    Async.edt(project) { OpenFileDescriptor(project, vf).navigate(true) }
                }
                reply.ok()
            }
            "reload" -> {
                store.reloadNow()
                reply.ok()
            }
            "savePrefs" -> {
                p.str("filter")?.let { PropertiesComponent.getInstance().setValue(FILTER_KEY, it) }
                p.str("snip")?.let { PropertiesComponent.getInstance().setValue(SNIP_KEY, it) }
                reply.ok()
            }
            else -> reply.fail("Неизвестный метод: $method")
        }
    }

    companion object {
        private const val FILTER_KEY = "aiReview.list.filter"
        private const val SNIP_KEY = "aiReview.list.snip"
    }
}
