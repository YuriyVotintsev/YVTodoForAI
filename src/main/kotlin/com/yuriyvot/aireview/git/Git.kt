package com.yuriyvot.aireview.git

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class GitException(message: String) : Exception(message)

class Git(val root: File) {

    class Result(val exit: Int, val stdout: ByteArray, val stderr: String) {
        val text: String get() = String(stdout, Charsets.UTF_8)
    }

    fun exec(args: List<String>, timeoutSeconds: Long = 120): Result {
        val command = listOf(
            executable(),
            "-c", "core.quotepath=false",
            "-c", "core.safecrlf=false",
            "-c", "color.ui=never",
        ) + args
        val process = try {
            ProcessBuilder(command).directory(root).also {
                it.environment()["GIT_TERMINAL_PROMPT"] = "0"
                it.environment()["GIT_OPTIONAL_LOCKS"] = "0"
            }.start()
        } catch (e: IOException) {
            throw GitException("Не удалось запустить git: ${e.message}")
        }
        process.outputStream.close()
        var stderr = ByteArray(0)
        val errReader = Thread({ stderr = process.errorStream.readBytes() }, "git-stderr").apply {
            isDaemon = true
            start()
        }
        val stdout = process.inputStream.readBytes()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw GitException("git ${args.joinToString(" ")}: превышено время ожидания")
        }
        errReader.join(5000)
        return Result(process.exitValue(), stdout, String(stderr, Charsets.UTF_8))
    }

    fun bytes(vararg args: String): ByteArray {
        val r = exec(args.toList())
        if (r.exit != 0) throw GitException("git ${args.joinToString(" ")}: ${r.stderr.trim().take(600)}")
        return r.stdout
    }

    fun text(vararg args: String): String = String(bytes(*args), Charsets.UTF_8)

    fun line(vararg args: String): String = text(*args).trim()

    fun lineOrNull(vararg args: String): String? {
        val r = exec(args.toList())
        return if (r.exit == 0) r.text.trim().ifEmpty { null } else null
    }

    fun succeeds(vararg args: String): Boolean = exec(args.toList()).exit == 0

    companion object {
        @Volatile
        private var cachedExecutable: String? = null

        private val CANDIDATES = listOf(
            "git",
            "C:\\Program Files\\Git\\cmd\\git.exe",
            "C:\\Program Files\\Git\\bin\\git.exe",
            "C:\\Program Files (x86)\\Git\\cmd\\git.exe",
            "/usr/bin/git",
            "/usr/local/bin/git",
            "/opt/homebrew/bin/git",
        )

        fun executable(): String {
            cachedExecutable?.let { return it }
            val found = CANDIDATES.firstOrNull { candidate ->
                try {
                    val p = ProcessBuilder(candidate, "--version").redirectErrorStream(true).start()
                    p.inputStream.readBytes()
                    p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
                } catch (_: Exception) {
                    false
                }
            } ?: throw GitException("git не найден ни в PATH, ни в стандартных папках")
            cachedExecutable = found
            return found
        }

        fun findRoot(dir: File): File? {
            if (!dir.isDirectory) return null
            val r = try {
                Git(dir).exec(listOf("rev-parse", "--show-toplevel"), 30)
            } catch (_: GitException) {
                return null
            }
            if (r.exit != 0) return null
            val path = r.text.trim().ifEmpty { return null }
            return File(path).canonicalFile
        }
    }
}
