package com.yuriyvot.aireview.comments

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.yuriyvot.aireview.diffview.ReviewSession
import com.yuriyvot.aireview.git.Git
import com.yuriyvot.aireview.util.RepoPaths
import java.io.File
import com.yuriyvot.aireview.git.DiffEnds
import com.yuriyvot.aireview.model.CommentFile
import com.yuriyvot.aireview.model.CommentStatus
import com.yuriyvot.aireview.model.ReviewComment
import com.yuriyvot.aireview.model.ThreadMessage
import com.yuriyvot.aireview.store.CommentStore
import com.yuriyvot.aireview.util.Async
import com.yuriyvot.aireview.util.ExplorerNav
import com.yuriyvot.aireview.util.Notify
import com.yuriyvot.aireview.util.ProjectPaths
import com.yuriyvot.aireview.web.Reply
import java.nio.file.Files

fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

fun JsonObject.int(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()?.toInt()

object CommentOps {

    fun commentsJson(project: Project): JsonArray {
        val base = ProjectPaths.base(project)
        return JsonArray().apply {
            CommentStore.getInstance(project).comments().forEach { c ->
                val json = CommentFile.toJson(c)
                val kind = when {
                    c.filePath == null -> "general"
                    c.startLine != null -> "lines"
                    else -> {
                        val abs = base?.let { ProjectPaths.resolve(it, c.filePath) }
                        if (abs != null && Files.isDirectory(abs)) "dir" else "file"
                    }
                }
                json.addProperty("_kind", kind)
                add(json)
            }
        }
    }

    fun handle(project: Project, method: String, p: JsonObject, reply: Reply): Boolean {
        val store = CommentStore.getInstance(project)
        when (method) {
            "updateComment" -> {
                val id = p.str("id") ?: return fail(reply)
                val text = p.str("text")?.trim().orEmpty()
                if (text.isEmpty()) {
                    reply.fail("Пустой комментарий")
                    return true
                }
                val start = p.int("startLine")
                val end = p.int("endLine")
                reply.async {
                    val current = store.find(id) ?: error("Комментарий не найден")
                    val rangeChanged = current.startLine != null && start != null &&
                        (start != current.startLine || (end ?: start) != (current.endLine ?: current.startLine))
                    if (rangeChanged) {
                        val from = minOf(start!!, end ?: start)
                        val to = maxOf(start, end ?: start)
                        val selected = CommentText.lines(project, current, from, to)
                        store.update(id) {
                            it.copy(comment = text, startLine = from, endLine = to, selectedText = selected ?: it.selectedText, diffSnippet = null)
                        }
                    } else {
                        store.update(id) { it.copy(comment = text) }
                    }
                    JsonNull.INSTANCE
                }
            }
            "reply" -> {
                val id = p.str("id") ?: return fail(reply)
                val text = p.str("text")?.trim().orEmpty()
                if (text.isEmpty()) {
                    reply.fail("Пустое сообщение")
                    return true
                }
                reply.async {
                    store.update(id) {
                        it.copy(
                            thread = it.thread + ThreadMessage(ThreadMessage.USER, text),
                            status = if (it.status == CommentStatus.IN_PROGRESS) it.status else CommentStatus.PENDING,
                        )
                    }
                    JsonNull.INSTANCE
                }
            }
            "deleteComment" -> {
                val id = p.str("id") ?: return fail(reply)
                reply.async {
                    store.remove(id)
                    JsonNull.INSTANCE
                }
            }
            "setStatus" -> {
                val id = p.str("id") ?: return fail(reply)
                val status = CommentStatus.parse(p.str("status"))
                reply.async {
                    store.update(id) { it.copy(status = status) }
                    JsonNull.INSTANCE
                }
            }
            "answer" -> {
                val id = p.str("id") ?: return fail(reply)
                val index = p.int("index") ?: return fail(reply)
                val answer = p.str("answer")?.trim()?.ifEmpty { null }
                reply.async {
                    store.update(id) { c ->
                        c.copy(questions = c.questions.mapIndexed { i, q -> if (i == index) q.copy(answer = answer) else q })
                    }
                    JsonNull.INSTANCE
                }
            }
            "navigate" -> {
                val comment = p.str("id")?.let { store.find(it) } ?: return fail(reply)
                CommentNavigator.navigate(project, comment)
                reply.ok()
            }
            else -> return false
        }
        return true
    }

    private fun fail(reply: Reply): Boolean {
        reply.fail("Комментарий не найден")
        return true
    }
}

object CommentNavigator {
    fun navigate(project: Project, comment: ReviewComment) {
        val path = comment.filePath ?: return
        val absolute = ProjectPaths.absolute(project, path)
        if (absolute != null && Files.isDirectory(absolute)) {
            ExplorerNav.showInFolder(project, absolute.toFile())
            return
        }
        ReviewSession.getInstance(project).openComment(comment)
    }
}

object CommentText {
    /** Text of lines [from]..[to] (1-based) of the file the comment points to, in the version it refers to. */
    fun lines(project: Project, comment: ReviewComment, from: Int, to: Int): String? {
        val root = ReviewSession.findRoot(project) ?: return null
        val repoPath = comment.filePath?.let { RepoPaths(root, project).toRepo(it) } ?: return null
        val bytes = when (val revision = comment.revision) {
            null -> File(root, repoPath).takeIf { it.isFile }?.readBytes()
            else -> Git(root).exec(listOf("cat-file", "blob", "$revision:$repoPath")).takeIf { it.exit == 0 }?.stdout
        } ?: return null
        val lines = String(bytes, Charsets.UTF_8).replace("\r\n", "\n").split('\n')
        val a = (from - 1).coerceIn(0, lines.size)
        val b = to.coerceIn(a, lines.size)
        return lines.subList(a, b).joinToString("\n")
    }
}
