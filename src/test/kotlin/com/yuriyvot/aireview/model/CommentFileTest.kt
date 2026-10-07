package com.yuriyvot.aireview.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentFileTest {

    @Test
    fun readsLegacyFileAndKeepsUnknownFields() {
        val legacy = """
            {
              "_instructions": "old",
              "comments": [
                {
                  "id": "1",
                  "filePath": "Assets\\A.cs",
                  "startLine": 3,
                  "endLine": 5,
                  "selectedText": "x = 1",
                  "comment": "rename",
                  "commitHash": "abc",
                  "status": "HAS_QUESTIONS",
                  "questions": [{"question": "Why?", "options": ["a", "b"], "answer": null}],
                  "createdAt": 1700000000000,
                  "custom": {"keep": true}
                },
                {"id": "2", "comment": "general", "status": "weird"}
              ]
            }
        """.trimIndent()

        val comments = CommentFile.parse(legacy)
        assertEquals(2, comments.size)
        val first = comments[0]
        assertEquals("Assets/A.cs", first.filePath)
        assertEquals("x = 1", first.selectedText)
        assertEquals(CommentStatus.HAS_QUESTIONS, first.status)
        assertEquals(listOf("a", "b"), first.questions.single().options)
        assertEquals(CommentStatus.PENDING, comments[1].status)

        val text = CommentFile.render(comments)
        assertTrue(text.contains("\"custom\""))
        assertTrue(text.contains("\"answer\": null"))
        assertTrue(text.contains("x = 1"))
        assertEquals(comments, CommentFile.parse(text))
    }

    @Test
    fun threadAndInProgressStatusRoundTrip() {
        val comment = ReviewComment(
            id = "t1",
            filePath = "A.cs",
            comment = "rename",
            status = CommentStatus.IN_PROGRESS,
            thread = listOf(ThreadMessage(ThreadMessage.USER, "why?", 1), ThreadMessage(ThreadMessage.CLAUDE, "because", 2)),
            createdAt = 5,
        )
        val parsed = CommentFile.parse(CommentFile.render(listOf(comment)))
        assertEquals(listOf(comment), parsed)
        assertTrue(parsed.single().thread.first().fromUser)
    }

    @Test
    fun emptyTextMeansNoComments() {
        assertEquals(emptyList<ReviewComment>(), CommentFile.parse("  \n"))
    }
}
