package com.yuriyvot.aireview.store

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.Topic
import com.yuriyvot.aireview.git.Git
import com.intellij.util.concurrency.AppExecutorUtil
import com.yuriyvot.aireview.model.CommentFile
import com.yuriyvot.aireview.model.CommentStatus
import com.yuriyvot.aireview.model.ReviewComment
import com.yuriyvot.aireview.util.Async
import com.yuriyvot.aireview.util.Notify
import com.yuriyvot.aireview.util.ProjectPaths
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class CommentStore(private val project: Project) : Disposable {

    fun interface Listener {
        fun commentsChanged()
    }

    @Volatile
    private var comments: List<ReviewComment> = emptyList()

    @Volatile
    var loadError: String? = null
        private set

    private val lock = Any()
    private var lastText: String? = null
    private var notifiedError: String? = null
    private val excludeChecked = AtomicBoolean(false)

    val file: Path? = ProjectPaths.base(project)?.resolve(CommentFile.FILE_NAME)

    private var poller: ScheduledFuture<*>? = null

    @Volatile
    private var lastStamp: Pair<Long, Long>? = null

    init {
        reload()
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val target = file ?: return
                val relevant = events.any { e ->
                    e.path.endsWith(CommentFile.FILE_NAME) && ProjectPaths.toPath(e.path) == target
                }
                if (relevant) Async.background { if (reload()) publish() }
            }
        })
        poller = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            try {
                val target = file ?: return@scheduleWithFixedDelay
                val stamp = if (Files.isRegularFile(target)) Files.getLastModifiedTime(target).toMillis() to Files.size(target) else null
                if (stamp != lastStamp) {
                    lastStamp = stamp
                    if (reload()) publish()
                }
            } catch (e: Exception) {
                LOG.debug(e)
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    fun comments(): List<ReviewComment> = comments

    fun find(id: String): ReviewComment? = comments.firstOrNull { it.id == id }

    fun add(comment: ReviewComment): Boolean = mutate { it.add(comment) }

    fun update(id: String, transform: (ReviewComment) -> ReviewComment): Boolean = mutate { list ->
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) list[index] = transform(list[index])
    }

    fun remove(id: String): Boolean = mutate { list -> list.removeIf { it.id == id } }

    fun removeClosed(): Boolean = mutate { list -> list.removeIf { it.status == CommentStatus.CLOSED } }

    fun closeDone(): Boolean = mutate { list ->
        list.replaceAll { if (it.status == CommentStatus.DONE || it.status == CommentStatus.CANCELED) it.copy(status = CommentStatus.CLOSED) else it }
    }

    fun reloadNow() {
        Async.background { if (reload()) publish() }
    }

    private fun reload(): Boolean {
        val target = file ?: return false
        synchronized(lock) {
            val text = readText(target)
            if (text == lastText && loadError == null) return false
            if (text == null) {
                val changed = comments.isNotEmpty() || loadError != null
                comments = emptyList()
                lastText = null
                loadError = null
                return changed
            }
            return try {
                comments = CommentFile.parse(text)
                lastText = text
                loadError = null
                notifiedError = null
                true
            } catch (e: Exception) {
                reportError(e)
                true
            }
        }
    }

    private fun mutate(block: (MutableList<ReviewComment>) -> Unit): Boolean {
        val target = file ?: return false
        synchronized(lock) {
            val current = readParsed(target) ?: return false
            val list = current.toMutableList()
            block(list)
            val text = CommentFile.render(list)
            try {
                writeAtomically(target, text)
            } catch (e: Exception) {
                LOG.warn(e)
                Notify.error(project, "Не удалось сохранить ${CommentFile.FILE_NAME}: ${e.message}")
                return false
            }
            comments = list
            lastText = text
            loadError = null
            notifiedError = null
        }
        publish()
        VfsUtil.markDirtyAndRefresh(true, false, false, target.toFile())
        ensureGitExcluded(target)
        return true
    }

    private fun readParsed(target: Path): List<ReviewComment>? {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            val text = readText(target) ?: return emptyList()
            try {
                return CommentFile.parse(text)
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) Thread.sleep(150)
            }
        }
        reportError(lastError!!)
        publish()
        return null
    }

    private fun reportError(e: Exception) {
        val message = e.message ?: e.javaClass.simpleName
        loadError = message
        if (notifiedError != message) {
            notifiedError = message
            Notify.error(
                project,
                "${CommentFile.FILE_NAME} не читается: $message. Изменения не сохраняются, пока файл не будет исправлен.",
            )
        }
    }

    private fun publish() {
        Async.edt(project, anyModality = true) {
            project.messageBus.syncPublisher(TOPIC).commentsChanged()
        }
    }

    private fun readText(target: Path): String? {
        if (!Files.isRegularFile(target)) return null
        val text = String(Files.readAllBytes(target), StandardCharsets.UTF_8)
        return text.removePrefix("﻿")
    }

    private fun writeAtomically(target: Path, text: String) {
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.write(tmp, text.toByteArray(StandardCharsets.UTF_8))
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            Files.write(target, text.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun ensureGitExcluded(target: Path) {
        if (!excludeChecked.compareAndSet(false, true)) return
        Async.background {
            val root = Git.findRoot(target.parent.toFile()) ?: return@background
            val git = Git(root)
            val check = git.exec(listOf("check-ignore", "-q", "--", target.toString()))
            if (check.exit != 1) return@background
            val excludeRel = git.lineOrNull("rev-parse", "--git-path", "info/exclude") ?: return@background
            val exclude = File(excludeRel).let { if (it.isAbsolute) it else File(root, excludeRel) }
            exclude.parentFile?.mkdirs()
            val existing = if (exclude.isFile) exclude.readText() else ""
            val prefix = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
            exclude.appendText("$prefix# YVTodoForAI review comments\n${CommentFile.FILE_NAME}*\n")
        }
    }

    override fun dispose() {
        poller?.cancel(false)
    }

    companion object {
        private val LOG = Logger.getInstance(CommentStore::class.java)

        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("AI Review comments", Listener::class.java)

        fun getInstance(project: Project): CommentStore = project.service()
    }
}
