package com.yuriyvot.aireview.web

import com.google.gson.JsonObject
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.swing.UIManager

object Theme {

    private val TOKENS: List<Pair<String, TextAttributesKey>> = listOf(
        "keyword" to DefaultLanguageHighlighterColors.KEYWORD,
        "string" to DefaultLanguageHighlighterColors.STRING,
        "number" to DefaultLanguageHighlighterColors.NUMBER,
        "comment" to DefaultLanguageHighlighterColors.LINE_COMMENT,
        "doc" to DefaultLanguageHighlighterColors.DOC_COMMENT,
        "type" to DefaultLanguageHighlighterColors.CLASS_NAME,
        "func" to DefaultLanguageHighlighterColors.FUNCTION_DECLARATION,
        "const" to DefaultLanguageHighlighterColors.CONSTANT,
        "meta" to DefaultLanguageHighlighterColors.METADATA,
        "field" to DefaultLanguageHighlighterColors.INSTANCE_FIELD,
        "tag" to DefaultLanguageHighlighterColors.MARKUP_TAG,
        "attr" to DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE,
        "param" to DefaultLanguageHighlighterColors.PARAMETER,
        "local" to DefaultLanguageHighlighterColors.LOCAL_VARIABLE,
        "escape" to DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE,
        "builtin" to DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL,
    )

    fun state(): JsonObject {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val dark = !JBColor.isBright()
        val vars = JsonObject()
        fun put(name: String, color: Color?) {
            if (color != null) vars.addProperty(name, css(color))
        }

        val panel = UIUtil.getPanelBackground()
        val editorBg = scheme.defaultBackground
        val editorFg = scheme.defaultForeground
        put("bg", panel)
        put("fg", UIUtil.getLabelForeground())
        put("fg-dim", NamedColorUtil.getInactiveTextColor())
        put("border", JBColor.border())
        put("sel-bg", UIUtil.getListSelectionBackground(true))
        put("sel-fg", UIUtil.getListSelectionForeground(true))
        put("sel-bg-inactive", UIUtil.getListSelectionBackground(false))
        put("hover-bg", JBUI.CurrentTheme.List.Hover.background(true))
        put("link", JBUI.CurrentTheme.Link.Foreground.ENABLED)
        put("input-bg", UIUtil.getTextFieldBackground())
        put("input-fg", UIUtil.getTextFieldForeground())
        put("focus", JBUI.CurrentTheme.Focus.focusColor())
        put("btn-bg", JBUI.CurrentTheme.Button.buttonColorStart())
        put("btn-fg", UIManager.getColor("Button.foreground") ?: UIUtil.getLabelForeground())
        put("btn-border", JBUI.CurrentTheme.Button.buttonOutlineColorStart(false))
        put("btn-default-bg", JBUI.CurrentTheme.Button.defaultButtonColorStart())
        put("btn-default-fg", UIManager.getColor("Button.default.foreground") ?: Color.WHITE)
        put("ed-bg", editorBg)
        put("ed-fg", editorFg)
        put("gutter-bg", scheme.getColor(EditorColors.GUTTER_BACKGROUND) ?: editorBg)
        put("ln-fg", scheme.getColor(EditorColors.LINE_NUMBERS_COLOR) ?: NamedColorUtil.getInactiveTextColor())
        put("ed-sel", scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR))

        for ((name, key) in TOKENS) {
            val attrs = scheme.getAttributes(key) ?: continue
            put("tok-$name", attrs.foregroundColor ?: editorFg)
            vars.addProperty("tok-$name-fs", if (attrs.fontType and Font.ITALIC != 0) "italic" else "normal")
            vars.addProperty("tok-$name-fw", if (attrs.fontType and Font.BOLD != 0) "600" else "normal")
        }

        val ui = JBFont.label()
        vars.addProperty("ui-font", "'IdeUiFont', ${quote(ui.family)}, 'Segoe UI', system-ui, sans-serif")
        vars.addProperty("ui-size", "${ui.size}px")
        vars.addProperty("ed-font", "'IdeEditorFont', ${quote(scheme.editorFontName)}, 'JetBrains Mono', 'Cascadia Mono', Consolas, monospace")
        vars.addProperty("ed-size", "${scheme.editorFontSize}px")
        vars.addProperty("ed-lh", scheme.lineSpacing.coerceIn(1.0f, 2.5f).toString())

        return JsonObject().apply {
            addProperty("dark", dark)
            add("vars", vars)
        }
    }

    fun fontFaces(): String {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val editor = scheme.editorFontName
        val ui = JBFont.label().family
        return fontCache.computeIfAbsent("$editor|$ui") {
            buildString {
                append(face("IdeEditorFont", editor, "Regular", "normal", "normal"))
                append(face("IdeEditorFont", editor, "Italic", "normal", "italic"))
                append(face("IdeEditorFont", editor, "Bold", "700", "normal"))
                append(face("IdeUiFont", ui, "Regular", "normal", "normal"))
                append(face("IdeUiFont", ui, "SemiBold", "600", "normal"))
            }
        }
    }

    private val fontCache = ConcurrentHashMap<String, String>()

    private fun face(alias: String, family: String, style: String, weight: String, fontStyle: String): String {
        val dir = Path.of(System.getProperty("java.home"), "lib", "fonts")
        val base = family.replace(" ", "")
        val file = listOf("ttf", "otf").map { dir.resolve("$base-$style.$it") }.firstOrNull { Files.isRegularFile(it) }
            ?: return ""
        val format = if (file.toString().endsWith("otf")) "opentype" else "truetype"
        val data = Base64.getEncoder().encodeToString(Files.readAllBytes(file))
        return "@font-face{font-family:'$alias';src:url(data:font/${file.toString().substringAfterLast('.')};base64,$data) format('$format');" +
            "font-weight:$weight;font-style:$fontStyle;font-display:block;}\n"
    }

    private fun quote(family: String) = "'" + family.replace("'", "") + "'"

    private fun css(c: Color): String =
        if (c.alpha == 255) "#%02x%02x%02x".format(c.red, c.green, c.blue)
        else "rgba(${c.red},${c.green},${c.blue},${"%.3f".format(java.util.Locale.ROOT, c.alpha / 255.0)})"
}
