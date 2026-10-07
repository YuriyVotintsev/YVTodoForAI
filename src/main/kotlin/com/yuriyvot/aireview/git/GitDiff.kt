package com.yuriyvot.aireview.git

import java.io.File

data class CommitInfo(val sha: String, val short: String, val subject: String, val date: String)

data class ChangedFile(
    val status: String,
    val path: String,
    val oldPath: String?,
    val added: Int?,
    val deleted: Int?,
    val binary: Boolean,
    val oldBlob: String? = null,
    val newBlob: String? = null,
)

data class DiffEnds(val base: String, val target: String?) {
    val rangeId: String get() = "$base..${target ?: WORKTREE}"

    companion object {
        const val WORKTREE = "WORKTREE"

        fun parse(range: String): DiffEnds? {
            val parts = range.split("..")
            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
            return DiffEnds(parts[0], parts[1].takeUnless { it == WORKTREE })
        }
    }
}

data class BranchState(
    val current: String?,
    val head: CommitInfo,
    val baseRef: String?,
    val candidates: List<String>,
    val mergedVia: CommitInfo?,
    val includeWorktree: Boolean,
)

data class ResolvedDiff(
    val ends: DiffEnds,
    val baseInfo: CommitInfo?,
    val targetInfo: CommitInfo?,
    val branch: BranchState?,
    val files: List<ChangedFile>,
)

sealed interface Content {
    data object Missing : Content
    data class TooLarge(val size: Long) : Content
    class Bytes(val data: ByteArray) : Content
}

object GitDiff {
    const val EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
    private const val MAX_UNTRACKED = 3000
    private val DEFAULT_BASES = listOf("origin/main", "main", "origin/master", "master", "origin/develop", "develop")

    fun currentBranch(git: Git): String? = git.lineOrNull("symbolic-ref", "--short", "-q", "HEAD")


    fun commitInfo(git: Git, rev: String): CommitInfo {
        val parts = git.text("log", "-1", "--no-show-signature", "--format=%H%x1f%h%x1f%s%x1f%cs", rev, "--")
            .trim().split('\u001f')
        if (parts.size < 4) throw GitException("Не удалось прочитать коммит $rev")
        return CommitInfo(parts[0], parts[1], parts[2], parts[3])
    }

    fun branchRefs(git: Git): List<String> =
        git.text(
            "for-each-ref", "--sort=-committerdate", "--format=%(refname)%09%(refname:short)",
            "refs/heads", "refs/remotes",
        ).lineSequence()
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab < 0) return@mapNotNull null
                val full = line.substring(0, tab)
                if (full.endsWith("/HEAD")) null else line.substring(tab + 1).trim().ifEmpty { null }
            }
            .toList()

    fun resolveBranch(git: Git, preferredBase: String?, includeWorktree: Boolean): ResolvedDiff {
        val head = commitInfo(git, "HEAD")
        val current = currentBranch(git)
        val refs = branchRefs(git)
        val refSet = refs.toHashSet()
        val candidates = (DEFAULT_BASES.filter { it in refSet } + refs).distinct().filter { it != current }
        val baseRef = preferredBase?.takeIf { it in refSet && it != current }
            ?: DEFAULT_BASES.firstOrNull { it in refSet && it != current }

        var mergedVia: CommitInfo? = null
        val base = when {
            baseRef == null -> head.sha
            git.succeeds("merge-base", "--is-ancestor", "HEAD", baseRef) -> {
                val first = git.text(
                    "rev-list", "--first-parent", "--ancestry-path", "--reverse", "--parents", "HEAD..$baseRef",
                ).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                val parts = first?.split(' ').orEmpty()
                val parents = parts.drop(1)
                if (parts.isNotEmpty() && parents.size >= 2 && parents[0] != head.sha) {
                    mergedVia = commitInfo(git, parts[0])
                    git.lineOrNull("merge-base", parents[0], "HEAD") ?: head.sha
                } else {
                    head.sha
                }
            }
            else -> git.lineOrNull("merge-base", baseRef, "HEAD") ?: head.sha
        }

        val ends = DiffEnds(base, if (includeWorktree) null else head.sha)
        return ResolvedDiff(
            ends = ends,
            baseInfo = commitInfo(git, base),
            targetInfo = if (includeWorktree) null else head,
            branch = BranchState(current, head, baseRef, candidates, mergedVia, includeWorktree),
            files = listFiles(git, ends),
        )
    }

    fun resolveRange(git: Git, ends: DiffEnds): ResolvedDiff = ResolvedDiff(
        ends = ends,
        baseInfo = if (ends.base == EMPTY_TREE) null else commitInfo(git, ends.base),
        targetInfo = ends.target?.let { commitInfo(git, it) },
        branch = null,
        files = listFiles(git, ends),
    )

    fun orderCommits(git: Git, revs: List<String>): DiffEnds {
        require(revs.size in 1..2) { "Нужно выбрать один или два коммита" }
        val shas = revs.map { git.line("rev-parse", "--verify", "$it^{commit}") }
        if (shas.size == 1 || shas[0] == shas[1]) {
            val parents = git.line("rev-list", "--parents", "-n", "1", shas[0]).split(' ').drop(1)
            return DiffEnds(parents.firstOrNull() ?: EMPTY_TREE, shas[0])
        }
        val (a, b) = shas
        return when {
            git.succeeds("merge-base", "--is-ancestor", a, b) -> DiffEnds(a, b)
            git.succeeds("merge-base", "--is-ancestor", b, a) -> DiffEnds(b, a)
            else -> {
                val ta = git.line("show", "-s", "--format=%ct", a).toLongOrNull() ?: 0
                val tb = git.line("show", "-s", "--format=%ct", b).toLongOrNull() ?: 0
                if (ta <= tb) DiffEnds(a, b) else DiffEnds(b, a)
            }
        }
    }

    fun listFiles(git: Git, ends: DiffEnds): List<ChangedFile> {
        val range = listOfNotNull(ends.base, ends.target).toTypedArray()
        val common = arrayOf("diff", "--no-ext-diff", "--no-color", "--no-textconv", "-M", "-z")
        val raw = splitZ(git.text(*common, "--raw", "--abbrev=40", *range, "--"))
        val numstat = splitZ(git.text(*common, "--numstat", *range, "--"))

        val stats = HashMap<String, Triple<Int?, Int?, Boolean>>()
        var i = 0
        while (i < numstat.size) {
            val parts = numstat[i++].split('\t', limit = 3)
            if (parts.size < 3) continue
            val path = if (parts[2].isEmpty()) {
                if (i + 1 >= numstat.size) break
                i++
                numstat[i++]
            } else {
                parts[2]
            }
            val binary = parts[0] == "-" || parts[1] == "-"
            stats[path] = Triple(parts[0].toIntOrNull(), parts[1].toIntOrNull(), binary)
        }

        val files = ArrayList<ChangedFile>()
        i = 0
        while (i < raw.size) {
            val header = raw[i++]
            if (!header.startsWith(":")) continue
            val fields = header.substring(1).split(' ')
            if (fields.size < 5) continue
            val oldBlob = fields[2].takeUnless { isZero(it) }
            val newBlob = fields[3].takeUnless { isZero(it) }
            val letter = fields[4].take(1)
            val (oldPath, path) = if (letter == "R" || letter == "C") {
                if (i + 1 >= raw.size) break
                raw[i++] to raw[i++]
            } else {
                if (i >= raw.size) break
                val p = raw[i++]
                (if (letter == "A") null else p) to p
            }
            val st = stats[path]
            files += ChangedFile(letter, path, oldPath, st?.first, st?.second, st?.third ?: false, oldBlob, newBlob)
        }

        if (ends.target == null) {
            val known = files.mapTo(HashSet()) { it.path }
            splitZ(git.text("ls-files", "--others", "--exclude-standard", "-z"))
                .asSequence()
                .filter { it.isNotEmpty() && it !in known }
                .take(MAX_UNTRACKED)
                .forEach { files += ChangedFile("?", it, null, null, null, false) }
        }
        return files.sortedBy { it.path.lowercase() }
    }

    fun readContent(git: Git, rev: String?, path: String, maxBytes: Long): Content {
        if (rev == null) {
            val file = File(git.root, path)
            if (!file.isFile) return Content.Missing
            val size = file.length()
            return if (size > maxBytes) Content.TooLarge(size) else Content.Bytes(file.readBytes())
        }
        if (rev == EMPTY_TREE) return Content.Missing
        val spec = "$rev:$path"
        val sizeResult = git.exec(listOf("cat-file", "-s", spec))
        if (sizeResult.exit != 0) return Content.Missing
        val size = sizeResult.text.trim().toLongOrNull() ?: return Content.Missing
        if (size > maxBytes) return Content.TooLarge(size)
        val blob = git.exec(listOf("cat-file", "blob", spec))
        return if (blob.exit == 0) Content.Bytes(blob.stdout) else Content.Missing
    }

    private fun isZero(sha: String) = sha.all { it == '0' }

    private fun splitZ(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val parts = text.split('\u0000')
        return if (parts.last().isEmpty()) parts.dropLast(1) else parts
    }
}
