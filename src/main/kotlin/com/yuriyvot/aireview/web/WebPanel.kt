package com.yuriyvot.aireview.web

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.HierarchyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JComponent
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

class Reply internal constructor(private val panel: WebPanel, private val id: Long) {
    fun ok(result: JsonElement = JsonNull.INSTANCE) = panel.resolve(id, true, result)

    fun fail(message: String) = panel.resolve(id, false, com.google.gson.JsonPrimitive(message))

    fun async(action: () -> JsonElement) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ok(action())
            } catch (e: ProcessCanceledException) {
                fail("Отменено")
            } catch (e: Throwable) {
                LOG.warn(e)
                fail(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(Reply::class.java)
    }
}

class WebPanel(
    parent: Disposable,
    private val page: String,
    private val bootExtra: () -> JsonObject,
    private val handler: (method: String, params: JsonObject, reply: Reply) -> Unit,
) : Disposable {

    private val browser: JBCefBrowser?
    private val query: JBCefJSQuery?
    val component: JComponent

    init {
        Disposer.register(parent, this)
        if (!JBCefApp.isSupported()) {
            browser = null
            query = null
            component = JBLabel("Встроенный браузер (JCEF) недоступен в этой IDE.", SwingConstants.CENTER).apply {
                border = JBUI.Borders.empty(20)
            }
        } else {
            val b = JBCefBrowser.createBuilder().setEnableOpenDevToolsMenuItem(true).build()
            Disposer.register(this, b)
            val q = JBCefJSQuery.create(b as JBCefBrowserBase)
            Disposer.register(this, q)
            q.addHandler { message ->
                onMessage(message)
                JBCefJSQuery.Response("")
            }
            browser = b
            query = q
            component = b.component
            b.loadHTML(buildHtml(q, b.isOffScreenRendering))

            val view = b.component
            val mouse = AWTEventListener { e ->
                val source = (e as? MouseEvent)?.component ?: return@AWTEventListener
                val inside = SwingUtilities.isDescendingFrom(source, view)
                when {
                    e.id == MouseEvent.MOUSE_PRESSED && !inside -> emit("outside", JsonNull.INSTANCE)
                    e is MouseWheelEvent && inside && !e.isShiftDown && !e.isControlDown && b.isOffScreenRendering -> {
                        val at = SwingUtilities.convertPoint(source, e.point, view)
                        emit("wheel", JsonObject().apply {
                            addProperty("x", at.x)
                            addProperty("y", at.y)
                            addProperty("lines", e.preciseWheelRotation * e.scrollAmount)
                            addProperty("pages", e.scrollType == MouseWheelEvent.WHEEL_BLOCK_SCROLL)
                        })
                    }
                }
            }
            Toolkit.getDefaultToolkit().addAWTEventListener(mouse, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK)
            Disposer.register(this) { Toolkit.getDefaultToolkit().removeAWTEventListener(mouse) }
            view.addHierarchyListener { e ->
                if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && !view.isShowing) {
                    emit("outside", JsonNull.INSTANCE)
                }
            }

            val bus = ApplicationManager.getApplication().messageBus.connect(this)
            bus.subscribe(LafManagerListener.TOPIC, LafManagerListener { emit("theme", Theme.state()) })
            bus.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { emit("theme", Theme.state()) })
        }
    }

    fun emit(event: String, payload: JsonElement) {
        execute("window.__ideEvent && window.__ideEvent(${gson.toJson(event)}, ${gson.toJson(payload)});")
    }

    internal fun resolve(id: Long, ok: Boolean, payload: JsonElement) {
        execute("window.__ideResolve && window.__ideResolve($id, $ok, ${gson.toJson(payload)});")
    }

    private fun execute(js: String) {
        val b = browser ?: return
        if (Disposer.isDisposed(this)) return
        b.cefBrowser.executeJavaScript(js, b.cefBrowser.url ?: "about:blank", 0)
    }

    private fun onMessage(message: String) {
        val json = try {
            JsonParser.parseString(message).asJsonObject
        } catch (e: Exception) {
            LOG.warn("Bad message from page: $message", e)
            return
        }
        val id = json.get("id")?.asLong ?: return
        val method = json.get("method")?.asString ?: return
        val params = json.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val reply = Reply(this, id)
        try {
            handler(method, params, reply)
        } catch (e: ProcessCanceledException) {
            reply.fail("Отменено")
        } catch (e: Throwable) {
            LOG.warn(e)
            reply.fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun buildHtml(q: JBCefJSQuery, ideWheel: Boolean): String {
        val boot = JsonObject().apply {
            addProperty("page", page)
            addProperty("ideWheel", ideWheel)
            add("theme", Theme.state())
            add("extra", bootExtra())
        }
        val send = q.inject("msg", "window.__ideNoop", "window.__ideNoop")
        val scripts = buildList {
            if (page == "file") add(resource("highlight.min.js"))
            add(resource("common.js"))
            add(resource("$page.js"))
        }
        return buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\">")
            append("<style>").append(Theme.fontFaces()).append("</style>")
            append("<style>").append(resource("common.css")).append("</style>")
            append("<style>").append(resource("$page.css")).append("</style>")
            append("</head><body class=\"page-").append(page).append("\"><div id=\"app\"></div>")
            append("<script>window.__ideNoop=function(){};window.__ideSend=function(msg){")
            append(send)
            append("};window.__BOOT=").append(htmlSafeGson.toJson(boot)).append(";</script>")
            scripts.forEach { append("<script>").append(it).append("</script>") }
            append("</body></html>")
        }
    }

    override fun dispose() {}

    companion object {
        private val LOG = Logger.getInstance(WebPanel::class.java)
        private val gson: Gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
        private val htmlSafeGson: Gson = GsonBuilder().serializeNulls().create()
        private val resources = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun resource(name: String): String = resources.computeIfAbsent(name) {
            WebPanel::class.java.getResourceAsStream("/web/$name")?.use { String(it.readBytes(), Charsets.UTF_8) }
                ?: error("Missing resource /web/$name")
        }
    }
}
