package com.yuriyvot.aireview.git

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GitDiffTest {
    private lateinit var dir: File
    private lateinit var git: Git

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("aireview-git").toFile()
        git = Git(dir)
        sh("init", "-q", "-b", "main")
        write("a.txt", "a1\n")
        commit("A")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun sh(vararg args: String): String {
        val p = ProcessBuilder(
            listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + args,
        ).directory(dir).redirectErrorStream(true).start()
        val out = String(p.inputStream.readBytes())
        check(p.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $out" }
        return out.trim()
    }

    private fun write(name: String, text: String) = File(dir, name).writeText(text)

    private fun commit(message: String): String {
        sh("add", "-A")
        sh("commit", "-q", "-m", message)
        return sh("rev-parse", "HEAD")
    }

    private fun paths(diff: ResolvedDiff) = diff.files.map { "${it.status} ${it.path}" }

    @Test
    fun branchNotMergedYet() {
        val a = sh("rev-parse", "HEAD")
        sh("checkout", "-q", "-b", "feature")
        write("f.txt", "f1\n")
        commit("F1")
        sh("checkout", "-q", "main")
        write("a.txt", "a2\n")
        val m1 = commit("M1")
        sh("checkout", "-q", "feature")
        sh("merge", "-q", "--no-edit", "main")
        write("f.txt", "f2\n")
        commit("F2")

        val diff = GitDiff.resolveBranch(git, null, includeWorktree = false)
        assertEquals("main", diff.branch!!.baseRef)
        assertNull(diff.branch!!.mergedVia)
        assertEquals(m1, diff.ends.base)
        assertEquals(listOf("A f.txt"), paths(diff))
        check(a != m1)
    }

    @Test
    fun branchAlreadyMergedIntoMain() {
        sh("checkout", "-q", "-b", "feature")
        write("f.txt", "f1\n")
        commit("F1")
        sh("checkout", "-q", "main")
        write("a.txt", "a2\n")
        val m1 = commit("M1")
        sh("checkout", "-q", "feature")
        sh("merge", "-q", "--no-edit", "main")
        write("f.txt", "f2\n")
        val f2 = commit("F2")
        sh("checkout", "-q", "main")
        write("a.txt", "a3\n")
        commit("M2")
        sh("merge", "-q", "--no-ff", "--no-edit", "feature")
        val merge = sh("rev-parse", "HEAD")
        write("a.txt", "a4\n")
        commit("M3 after merge")
        sh("checkout", "-q", "feature")

        val diff = GitDiff.resolveBranch(git, "main", includeWorktree = false)
        assertEquals(merge, diff.branch!!.mergedVia!!.sha)
        assertEquals(m1, diff.ends.base)
        assertEquals(f2, diff.ends.target)
        assertEquals(listOf("A f.txt"), paths(diff))
    }

    @Test
    fun worktreeChangesAndUntrackedFiles() {
        sh("checkout", "-q", "-b", "feature")
        write("f.txt", "f1\n")
        commit("F1")
        write("f.txt", "f1 changed\n")
        write("new file.txt", "n\n")
        File(dir, "a.txt").delete()

        val diff = GitDiff.resolveBranch(git, null, includeWorktree = true)
        assertNull(diff.ends.target)
        assertEquals(listOf("D a.txt", "A f.txt", "? new file.txt"), paths(diff))
        val untracked = diff.files.first { it.status == "?" }
        assertNull(untracked.oldPath)
        val content = GitDiff.readContent(git, null, "new file.txt", 1000)
        assertEquals("n\n", String((content as Content.Bytes).data))
        assertEquals(Content.Missing, GitDiff.readContent(git, diff.ends.base, "f.txt", 1000))
    }

    @Test
    fun renamesAndCommitOrdering() {
        write("big.txt", (1..50).joinToString("\n") { "line $it" } + "\n")
        val c1 = commit("C1")
        sh("mv", "big.txt", "moved.txt")
        val c2 = commit("C2")

        assertEquals(DiffEnds(c1, c2), GitDiff.orderCommits(git, listOf(c2, c1)))
        assertEquals(DiffEnds(c1, c2), GitDiff.orderCommits(git, listOf(c2)))
        val root = sh("rev-list", "--max-parents=0", "HEAD")
        assertEquals(DiffEnds(GitDiff.EMPTY_TREE, root), GitDiff.orderCommits(git, listOf(root)))

        val diff = GitDiff.resolveRange(git, DiffEnds(c1, c2))
        val renamed = diff.files.single()
        assertEquals("R", renamed.status)
        assertEquals("big.txt", renamed.oldPath)
        assertEquals("moved.txt", renamed.path)
        assertNotNull(renamed.oldBlob)

        val rootDiff = GitDiff.resolveRange(git, DiffEnds(GitDiff.EMPTY_TREE, root))
        assertNull(rootDiff.baseInfo)
        assertEquals(listOf("A a.txt"), paths(rootDiff))
    }

    @Test
    fun rangeIdRoundTrip() {
        val ends = DiffEnds("abc", null)
        assertEquals("abc..WORKTREE", ends.rangeId)
        assertEquals(ends, DiffEnds.parse(ends.rangeId))
        assertEquals(DiffEnds("a", "b"), DiffEnds.parse("a..b"))
        assertNull(DiffEnds.parse("garbage"))
    }
}
