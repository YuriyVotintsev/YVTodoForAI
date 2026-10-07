package com.yuriyvot.aireview.util

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project

object Notify {
    fun info(project: Project?, text: String) = show(project, text, NotificationType.INFORMATION)

    fun warn(project: Project?, text: String) = show(project, text, NotificationType.WARNING)

    fun error(project: Project?, text: String) = show(project, text, NotificationType.ERROR)

    private fun show(project: Project?, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup("AI Review")
            .createNotification("AI Review", text, type)
            .notify(project)
    }
}

object Async {
    private val LOG = Logger.getInstance(Async::class.java)

    fun background(action: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                action()
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Throwable) {
                LOG.warn(e)
            }
        }
    }

    fun edt(project: Project?, anyModality: Boolean = false, action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        val wrapped = Runnable {
            if (project == null || !project.isDisposed) action()
        }
        app.invokeLater(wrapped, if (anyModality) ModalityState.any() else ModalityState.defaultModalityState())
    }
}
