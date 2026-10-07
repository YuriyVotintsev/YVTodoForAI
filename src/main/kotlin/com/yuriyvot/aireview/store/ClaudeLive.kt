package com.yuriyvot.aireview.store

import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import com.yuriyvot.aireview.model.CommentFile
import com.yuriyvot.aireview.util.Async
import com.yuriyvot.aireview.util.ProjectPaths
import java.nio.file.Files
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class ClaudeLive(private val project: Project) : Disposable {

    fun interface Listener {
        fun liveChanged(alive: Boolean)
    }

    @Volatile
    var alive: Boolean = false
        private set

    private var task: ScheduledFuture<*>? = null

    @Synchronized
    fun start() {
        if (task != null) return
        task = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ check() }, 0, 3, TimeUnit.SECONDS)
    }

    private fun check() {
        val file = ProjectPaths.base(project)?.resolve(CommentFile.FILE_NAME + ".live") ?: return
        val now = try {
            if (!Files.isRegularFile(file)) false else {
                val heartbeat = JsonParser.parseString(Files.readString(file)).asJsonObject.get("heartbeat")?.asLong ?: 0L
                System.currentTimeMillis() - heartbeat < 15_000
            }
        } catch (_: Exception) {
            alive
        }
        if (now != alive) {
            alive = now
            Async.edt(project, anyModality = true) { project.messageBus.syncPublisher(TOPIC).liveChanged(now) }
        }
    }

    override fun dispose() {
        task?.cancel(false)
    }

    companion object {
        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("AI Review live Claude", Listener::class.java)

        fun getInstance(project: Project): ClaudeLive = project.service()
    }
}
