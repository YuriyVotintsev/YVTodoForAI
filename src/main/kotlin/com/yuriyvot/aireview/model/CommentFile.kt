package com.yuriyvot.aireview.model

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object CommentFile {
    const val FILE_NAME = ".ai-review-comments.json"

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()

    private val KNOWN = setOf(
        "id", "filePath", "startLine", "endLine", "selectedText", "comment", "commitHash", "revision",
        "diffRange", "diffSnippet", "status", "questions", "thread", "createdAt",
    )

    private val INSTRUCTIONS = """
Code review comments written in the IDE (YVTodoForAI plugin) for an AI assistant.
Process them with /review_comments (all at once) or /review_live (react to each comment as it appears).

Comment fields:
- id: unique id.
- filePath: path relative to the project root, '/' separators. A file or a folder. null = general comment.
- startLine / endLine: 1-based inclusive line range. null = the comment is about the whole file/folder.
- selectedText: the code the user selected; use it to re-locate the place if lines moved.
- revision: commit the line numbers refer to. null = the working-tree file at the moment the comment was written.
  When set, read that version with `git show <revision>:<path>` (git paths are relative to the repository root,
  which can differ from the project root).
- diffRange: "<base>..<target>" if the comment was written in the plugin's diff view (target "WORKTREE" =
  uncommitted state).
- diffSnippet: the selected diff rows in unified format (' ' context, '-' removed, '+' added).
- comment: what the user wants (a change request or a question).
- commitHash: HEAD at the moment the comment was written.
- status: PENDING | IN_PROGRESS | HAS_QUESTIONS | DONE | CANCELED | CLOSED (the user archived it after checking;
  never set it yourself and ignore such comments).
- questions: [{question, options, answer}] - questions from the AI; the user fills "answer" (in the IDE or chat).
- thread: the conversation about the comment, [{author: "user" | "claude", text, at}]. The user's messages are
  follow-ups to the comment; answer them in the thread.
- createdAt: unix time, ms.

Rules for the AI: change only status, questions and thread; keep unknown fields; never delete comments.
Prefer ~/.claude/scripts/ai_review.py (say / status / open / watch) over editing this file by hand.
The IDE picks up changes of this file automatically.
""".trim()

    fun parse(text: String): List<ReviewComment> {
        if (text.isBlank()) return emptyList()
        val root = JsonParser.parseString(text)
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.get("comments")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
            else -> throw IllegalArgumentException("Unexpected JSON root")
        }
        return array.mapNotNull { if (it.isJsonObject) fromJson(it.asJsonObject) else null }
    }

    fun render(comments: List<ReviewComment>): String {
        val root = JsonObject()
        root.addProperty("_instructions", INSTRUCTIONS)
        root.add("comments", JsonArray().apply { comments.forEach { add(toJson(it)) } })
        return gson.toJson(root) + "\n"
    }

    fun toJson(c: ReviewComment): JsonObject = JsonObject().apply {
        addProperty("id", c.id)
        addProperty("filePath", c.filePath)
        addProperty("startLine", c.startLine)
        addProperty("endLine", c.endLine)
        addProperty("selectedText", c.selectedText)
        addProperty("comment", c.comment)
        addProperty("commitHash", c.commitHash)
        if (c.revision != null) addProperty("revision", c.revision)
        if (c.diffRange != null) addProperty("diffRange", c.diffRange)
        if (c.diffSnippet != null) addProperty("diffSnippet", c.diffSnippet)
        addProperty("status", c.status.name)
        add("questions", JsonArray().apply {
            c.questions.forEach { q ->
                add(JsonObject().apply {
                    addProperty("question", q.question)
                    add("options", JsonArray().apply { q.options.forEach { add(it) } })
                    if (q.answer != null) addProperty("answer", q.answer) else add("answer", JsonNull.INSTANCE)
                })
            }
        })
        if (c.thread.isNotEmpty()) add("thread", JsonArray().apply {
            c.thread.forEach { m ->
                add(JsonObject().apply {
                    addProperty("author", m.author)
                    addProperty("text", m.text)
                    addProperty("at", m.at)
                })
            }
        })
        addProperty("createdAt", c.createdAt)
        c.extras.forEach { (k, v) -> add(k, v) }
    }

    fun fromJson(o: JsonObject): ReviewComment? {
        val id = o.str("id") ?: return null
        return ReviewComment(
            id = id,
            filePath = o.str("filePath")?.replace('\\', '/'),
            startLine = o.int("startLine"),
            endLine = o.int("endLine"),
            selectedText = o.str("selectedText"),
            comment = o.str("comment") ?: "",
            commitHash = o.str("commitHash"),
            revision = o.str("revision"),
            diffRange = o.str("diffRange"),
            diffSnippet = o.str("diffSnippet"),
            status = CommentStatus.parse(o.str("status")),
            questions = o.get("questions")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { q ->
                    if (!q.isJsonObject) return@mapNotNull null
                    val qo = q.asJsonObject
                    AIQuestion(
                        question = qo.str("question") ?: return@mapNotNull null,
                        options = qo.get("options")?.takeIf { it.isJsonArray }?.asJsonArray
                            ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString } ?: emptyList(),
                        answer = qo.str("answer"),
                    )
                } ?: emptyList(),
            thread = o.get("thread")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { m ->
                    if (!m.isJsonObject) return@mapNotNull null
                    val mo = m.asJsonObject
                    ThreadMessage(
                        author = mo.str("author") ?: ThreadMessage.CLAUDE,
                        text = mo.str("text") ?: return@mapNotNull null,
                        at = mo.long("at") ?: 0L,
                    )
                } ?: emptyList(),
            createdAt = o.long("createdAt") ?: 0L,
            extras = o.entrySet().filter { it.key !in KNOWN }.associate { it.key to it.value },
        )
    }

    private fun JsonObject.prim(key: String): JsonElement? =
        get(key)?.takeIf { it.isJsonPrimitive }

    private fun JsonObject.str(key: String): String? = prim(key)?.asString

    private fun JsonObject.int(key: String): Int? = prim(key)?.let { it.asString.trim().toDoubleOrNull()?.toInt() }

    private fun JsonObject.long(key: String): Long? = prim(key)?.let { it.asString.trim().toDoubleOrNull()?.toLong() }
}
