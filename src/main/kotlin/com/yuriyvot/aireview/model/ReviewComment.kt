package com.yuriyvot.aireview.model

import com.google.gson.JsonElement
import java.util.UUID

enum class CommentStatus {
    PENDING, IN_PROGRESS, HAS_QUESTIONS, DONE, CANCELED, CLOSED;

    val isOpen: Boolean get() = this == PENDING || this == IN_PROGRESS || this == HAS_QUESTIONS

    companion object {
        fun parse(value: String?): CommentStatus =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: PENDING
    }
}

data class AIQuestion(
    val question: String,
    val options: List<String> = emptyList(),
    val answer: String? = null,
)

data class ThreadMessage(
    val author: String,
    val text: String,
    val at: Long = System.currentTimeMillis(),
) {
    val fromUser: Boolean get() = author == USER

    companion object {
        const val USER = "user"
        const val CLAUDE = "claude"
    }
}

data class ReviewComment(
    val id: String = UUID.randomUUID().toString(),
    val filePath: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val selectedText: String? = null,
    val comment: String,
    val commitHash: String? = null,
    val revision: String? = null,
    val diffRange: String? = null,
    val diffSnippet: String? = null,
    val status: CommentStatus = CommentStatus.PENDING,
    val questions: List<AIQuestion> = emptyList(),
    val thread: List<ThreadMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val extras: Map<String, JsonElement> = emptyMap(),
)
