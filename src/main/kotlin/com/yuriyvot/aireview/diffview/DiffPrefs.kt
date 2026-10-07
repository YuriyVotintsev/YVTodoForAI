package com.yuriyvot.aireview.diffview

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project

object DiffPrefs {
    private const val PREFS = "aiReview.diff.prefs"

    fun load(): JsonObject = PropertiesComponent.getInstance().getValue(PREFS)?.let {
        runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull()
    } ?: JsonObject()

    fun save(prefs: JsonObject) = PropertiesComponent.getInstance().setValue(PREFS, prefs.toString())

    fun bool(key: String, default: Boolean): Boolean =
        load().get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: default

    fun setBool(key: String, value: Boolean) {
        val prefs = load()
        prefs.addProperty(key, value)
        save(prefs)
    }

    fun merge(update: JsonObject) {
        val prefs = load()
        update.entrySet().forEach { (k, v) -> prefs.add(k, v) }
        save(prefs)
    }

    fun baseFor(project: Project, branch: String?): String? =
        branch?.let { PropertiesComponent.getInstance(project).getValue("aiReview.base.$it") }

    fun saveBase(project: Project, branch: String?, ref: String?) {
        if (branch == null) return
        PropertiesComponent.getInstance(project).setValue("aiReview.base.$branch", ref)
    }
}
