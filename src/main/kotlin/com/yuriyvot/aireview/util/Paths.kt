package com.yuriyvot.aireview.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path

object ProjectPaths {
    fun base(project: Project): Path? = project.basePath?.let { toPath(it) }

    fun relative(project: Project, file: VirtualFile): String? {
        if (!file.isInLocalFileSystem) return null
        val base = base(project) ?: return null
        val path = toPath(file.path) ?: return null
        return relativize(base, path)
    }

    fun relative(project: Project, absolute: Path): String? {
        val base = base(project) ?: return null
        return relativize(base, absolute.normalize())
    }

    fun absolute(project: Project, relative: String): Path? = base(project)?.let { resolve(it, relative) }

    fun findFile(project: Project, relative: String): VirtualFile? {
        val abs = absolute(project, relative) ?: return null
        val fs = LocalFileSystem.getInstance()
        return fs.findFileByNioFile(abs) ?: fs.refreshAndFindFileByNioFile(abs)
    }

    fun relativize(base: Path, path: Path): String? = try {
        base.normalize().relativize(path.normalize()).toString().replace('\\', '/').ifEmpty { "." }
    } catch (_: IllegalArgumentException) {
        null
    }

    fun resolve(base: Path, relative: String): Path? = try {
        base.resolve(relative).normalize()
    } catch (_: InvalidPathException) {
        null
    }

    fun toPath(path: String): Path? = try {
        Path.of(path).toAbsolutePath().normalize()
    } catch (_: InvalidPathException) {
        null
    }
}

class RepoPaths(val repoRoot: File, project: Project) {
    private val root: Path = repoRoot.toPath().toAbsolutePath().normalize()
    private val base: Path? = ProjectPaths.base(project)

    fun toProject(repoRelative: String): String? {
        val b = base ?: return null
        return ProjectPaths.relativize(b, root.resolve(repoRelative))
    }

    fun toRepo(projectRelative: String): String? {
        val b = base ?: return null
        val abs = ProjectPaths.resolve(b, projectRelative) ?: return null
        val rel = ProjectPaths.relativize(root, abs) ?: return null
        return rel.takeUnless { it.startsWith("../") || it == ".." }
    }

    fun absolute(repoRelative: String): Path = root.resolve(repoRelative).normalize()
}
