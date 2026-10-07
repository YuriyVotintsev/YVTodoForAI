package com.yuriyvot.aireview.diffview

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.diff.fragments.LineFragment
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.yuriyvot.aireview.git.ChangedFile
import com.yuriyvot.aireview.git.Content
import com.yuriyvot.aireview.git.DiffEnds
import com.yuriyvot.aireview.git.Git
import com.yuriyvot.aireview.git.GitDiff
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

object FileDiffBuilder {
    private const val MAX_TEXT_BYTES = 3_000_000L
    private const val MAX_FORCED_BYTES = 40_000_000L
    private const val MAX_IMAGE_BYTES = 8_000_000L
    private const val MAX_INNER_CHARS = 1_500_000

    private val IMAGE_TYPES = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
        "webp" to "image/webp", "bmp" to "image/bmp", "ico" to "image/x-icon",
    )

    fun build(git: Git, ends: DiffEnds, file: ChangedFile, ignoreWhitespace: Boolean, force: Boolean): JsonObject {
        val out = JsonObject()
        val ext = file.path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        val imageType = IMAGE_TYPES[ext]
        val limit = when {
            imageType != null -> MAX_IMAGE_BYTES
            force -> MAX_FORCED_BYTES
            else -> MAX_TEXT_BYTES
        }

        val oldContent = file.oldPath?.let { GitDiff.readContent(git, ends.base, it, limit) } ?: Content.Missing
        val newContent = if (file.status == "D") Content.Missing else GitDiff.readContent(git, ends.target, file.path, limit)
        out.addProperty("oldExists", oldContent !is Content.Missing)
        out.addProperty("newExists", newContent !is Content.Missing)

        if (oldContent is Content.TooLarge || newContent is Content.TooLarge) {
            out.addProperty("kind", "tooLarge")
            out.addProperty("oldSize", sizeOf(oldContent))
            out.addProperty("newSize", sizeOf(newContent))
            out.addProperty("canForce", !force && imageType == null)
            return out
        }

        val oldBytes = (oldContent as? Content.Bytes)?.data
        val newBytes = (newContent as? Content.Bytes)?.data
        out.addProperty("oldSize", oldBytes?.size ?: 0)
        out.addProperty("newSize", newBytes?.size ?: 0)

        if (oldBytes == null && newBytes == null) {
            out.addProperty("kind", "empty")
            return out
        }

        if (imageType != null) {
            out.addProperty("kind", "image")
            oldBytes?.let { out.addProperty("oldImage", dataUrl(imageType, it)) }
            newBytes?.let { out.addProperty("newImage", dataUrl(imageType, it)) }
            return out
        }

        if (isBinary(oldBytes) || isBinary(newBytes)) {
            out.addProperty("kind", "binary")
            return out
        }

        val oldText = decode(oldBytes)
        val newText = decode(newBytes)
        out.addProperty("kind", "text")
        out.addProperty("oldText", oldText)
        out.addProperty("newText", newText)

        val fragments = compare(oldText, newText, ignoreWhitespace)
        val oldLines = LineIndex(oldText)
        val newLines = LineIndex(newText)
        val fragArray = JsonArray()
        val wordsOld = JsonObject()
        val wordsNew = JsonObject()
        for (f in fragments) {
            fragArray.add(JsonArray().apply {
                add(f.startLine1); add(f.endLine1); add(f.startLine2); add(f.endLine2)
            })
            val inner = f.innerFragments ?: continue
            for (d in inner) {
                addWordRanges(wordsOld, oldLines, f.startOffset1 + d.startOffset1, f.startOffset1 + d.endOffset1)
                addWordRanges(wordsNew, newLines, f.startOffset2 + d.startOffset2, f.startOffset2 + d.endOffset2)
            }
        }
        out.add("fragments", fragArray)
        out.add("words", JsonObject().apply {
            add("old", wordsOld)
            add("new", wordsNew)
        })
        return out
    }

    private fun compare(oldText: String, newText: String, ignoreWhitespace: Boolean): List<LineFragment> {
        val manager = ComparisonManager.getInstance()
        val policy = if (ignoreWhitespace) ComparisonPolicy.IGNORE_WHITESPACES else ComparisonPolicy.DEFAULT
        val indicator = EmptyProgressIndicator()
        return try {
            if (oldText.length + newText.length <= MAX_INNER_CHARS) {
                manager.compareLinesInner(oldText, newText, policy, indicator)
            } else {
                manager.compareLines(oldText, newText, policy, indicator)
            }
        } catch (_: DiffTooBigException) {
            try {
                manager.compareLines(oldText, newText, policy, indicator)
            } catch (_: DiffTooBigException) {
                emptyList()
            }
        }
    }

    private fun addWordRanges(target: JsonObject, lines: LineIndex, start: Int, end: Int) {
        if (end <= start) return
        var line = lines.lineOf(start)
        while (line < lines.count) {
            val lineStart = lines.start(line)
            val lineEnd = lines.end(line)
            if (lineStart >= end) break
            val from = maxOf(start, lineStart) - lineStart
            val to = minOf(end, lineEnd) - lineStart
            if (to > from) {
                val key = line.toString()
                val arr = target.getAsJsonArray(key) ?: JsonArray().also { target.add(key, it) }
                arr.add(JsonArray().apply { add(from); add(to) })
            }
            line++
        }
    }

    private class LineIndex(text: String) {
        private val starts: IntArray
        private val length = text.length

        init {
            val list = ArrayList<Int>()
            list.add(0)
            for (i in text.indices) if (text[i] == '\n') list.add(i + 1)
            starts = list.toIntArray()
        }

        val count: Int get() = starts.size

        fun start(line: Int): Int = starts[line]

        fun end(line: Int): Int = if (line + 1 < starts.size) starts[line + 1] - 1 else length

        fun lineOf(offset: Int): Int {
            var lo = 0
            var hi = starts.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (starts[mid] <= offset) lo = mid else hi = mid - 1
            }
            return lo
        }
    }

    private fun decode(bytes: ByteArray?): String {
        if (bytes == null) return ""
        var data = bytes
        if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) {
            data = data.copyOfRange(3, data.size)
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString()
        } catch (_: CharacterCodingException) {
            String(data, charset("windows-1251"))
        }
        return text.replace("\r\n", "\n").replace('\r', '\n')
    }

    private fun isBinary(bytes: ByteArray?): Boolean {
        if (bytes == null) return false
        val n = minOf(bytes.size, 8000)
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
        return false
    }

    private fun sizeOf(content: Content): Long = when (content) {
        is Content.TooLarge -> content.size
        is Content.Bytes -> content.data.size.toLong()
        Content.Missing -> 0
    }

    private fun dataUrl(type: String, bytes: ByteArray) = "data:$type;base64," + Base64.getEncoder().encodeToString(bytes)
}
