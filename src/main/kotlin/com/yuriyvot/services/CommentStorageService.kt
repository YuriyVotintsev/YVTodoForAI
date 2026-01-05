package com.yuriyvot.services

import com.yuriyvot.model.CommentStatus
import com.yuriyvot.model.ReviewComment
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class CommentStorageService(private val project: Project) {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val comments = CopyOnWriteArrayList<ReviewComment>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val storageFile: File
        get() = File(project.basePath, ".ai-review-comments.json")

    private data class StorageFormat(
        val _instructions: String = INSTRUCTIONS,
        val comments: List<ReviewComment> = emptyList()
    )

    companion object {
        private const val INSTRUCTIONS = """
This file contains code review comments for AI assistant.

Comment types:
1. Code comments - attached to specific code location (have filePath, startLine, etc.)
2. General comments - not attached to code (filePath/startLine/etc. are null)

Comment fields:
- id: Unique identifier (UUID)
- filePath: Relative path to file from project root (null for general comments)
- startLine: First line of selection, 1-based (null for general comments)
- endLine: Last line of selection, 1-based (null for general comments)
- selectedText: The code that was selected (null for general comments)
- comment: Review comment text (what needs to be done)
- commitHash: Git commit hash when comment was created (for reference)
- status: "PENDING", "HAS_QUESTIONS", "DONE", or "CANCELED"
- questions: Array of questions from AI (see below)
- createdAt: Unix timestamp (milliseconds)

Question structure (in questions array):
- question: The question text
- options: Array of answer options for AskUserQuestion tool
- answer: User's answer (filled after user responds)

AI Workflow:
1. Read PENDING comments and try to fix the code (or handle general tasks)
2. If unclear how to fix - add questions and set status to "HAS_QUESTIONS":
   "status": "HAS_QUESTIONS",
   "questions": [
     {"question": "Should I use async/await here?", "options": ["Yes, use async", "No, keep sync"], "answer": null},
     {"question": "Which error handling?", "options": ["Try-catch", "Result type", "Let it throw"], "answer": null}
   ]
3. On next pass - find HAS_QUESTIONS, use AskUserQuestion tool to ask all questions
4. Write answers back to JSON:
   {"question": "...", "options": [...], "answer": "Yes, use async"}
5. On next pass - read answers and fix the code, then set status to "DONE"
6. If user answers to cancel/postpone - set status to "CANCELED"

The IDE plugin will automatically detect changes and update the UI.
"""

        fun getInstance(project: Project): CommentStorageService =
            project.getService(CommentStorageService::class.java)
    }

    init {
        loadComments()
    }

    fun getComments(): List<ReviewComment> = comments.toList()

    fun getCommentsForFile(filePath: String): List<ReviewComment> {
        return comments.filter { it.filePath == filePath }
    }

    fun addComment(comment: ReviewComment) {
        comments.add(comment)
        saveComments()
        notifyListeners()
    }

    fun updateComment(id: String, updater: (ReviewComment) -> ReviewComment) {
        val index = comments.indexOfFirst { it.id == id }
        if (index >= 0) {
            comments[index] = updater(comments[index])
            saveComments()
            notifyListeners()
        }
    }

    fun removeComment(id: String) {
        comments.removeIf { it.id == id }
        saveComments()
        notifyListeners()
    }

    fun markAsDone(id: String) {
        updateComment(id) { it.copy(status = CommentStatus.DONE) }
    }

    fun clearDone() {
        comments.removeIf { it.status == CommentStatus.DONE }
        saveComments()
        notifyListeners()
    }

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeChangeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }

    private fun loadComments() {
        val file = storageFile
        if (file.exists()) {
            try {
                val json = file.readText()
                val storage = gson.fromJson(json, StorageFormat::class.java)
                comments.clear()
                if (storage?.comments != null) {
                    comments.addAll(storage.comments)
                }
            } catch (e: Exception) {
                comments.clear()
            }
        }
    }

    fun reloadFromDisk() {
        loadComments()
        notifyListeners()
    }

    private fun saveComments() {
        try {
            val storage = StorageFormat(comments = comments.toList())
            val json = gson.toJson(storage)
            storageFile.writeText(json)
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(storageFile)
        } catch (e: Exception) {
        }
    }
}
