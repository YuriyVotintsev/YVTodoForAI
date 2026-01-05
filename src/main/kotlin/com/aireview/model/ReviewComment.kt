package com.aireview.model

import java.util.UUID

enum class CommentStatus {
    PENDING,
    HAS_QUESTIONS,
    DONE,
    CANCELED
}

data class AIQuestion(
    val question: String,
    val options: List<String>? = null,
    val answer: String? = null
) {
    fun optionsOrEmpty(): List<String> = options ?: emptyList()
}

data class ReviewComment(
    val id: String = UUID.randomUUID().toString(),
    val filePath: String? = null,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val selectedText: String? = null,
    val comment: String,
    val commitHash: String? = null,
    val status: CommentStatus = CommentStatus.PENDING,
    val questions: List<AIQuestion>? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun questionsOrEmpty(): List<AIQuestion> = questions ?: emptyList()
    fun isCodeComment(): Boolean = filePath != null && startLine != null
}
