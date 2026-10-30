package nl.hicts.mph.intellij.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal data class GitCommandResult(val exitCode: Int, val output: String, val stderr: String = "") {
    val diagnostic: String get() = GitDiagnostics.redact(listOf(output, stderr).filter { it.isNotBlank() }.joinToString("\n"))
}

internal fun interface GitCommandRunner {
    fun execute(root: Path, arguments: List<String>, progress: ((String) -> Unit)?, environment: Map<String, String>): GitCommandResult
}

internal object GitDiagnostics {
    private val userInfo = Regex("(?i)(?<![a-z0-9+.-])([a-z][a-z0-9+.-]*://)[^\\s/]*@")
    private val querySecret = Regex("([?&][^\\s=&#?]+=)[^\\s&#]*")
    private val authorization = Regex("(?i)((?:proxy-)?authorization[=:]\\s*)(?:basic|bearer)\\s+[^\\s]+")
    fun redact(text: String): String = authorization.replace(querySecret.replace(userInfo.replace(text, "$1[redacted]@"), "$1[redacted]"), "$1[redacted]")
}

/** Drain both pipes without waiting for EOF: a credential helper or hook can inherit them. */
internal class NativeGitCommandRunner(
    private val executable: List<String> = listOf("git"),
    private val readTimeoutMillis: Long = 120_000,
    private val warningMillis: Long = 30_000,
) : GitCommandRunner {
    override fun execute(root: Path, arguments: List<String>, progress: ((String) -> Unit)?, environment: Map<String, String>): GitCommandResult {
        var result: GitCommandResult? = null
        val run = Runnable { result = executeProcess(root, arguments, progress, environment) }
        // Never let IDE cancellation abandon a mutating process and release the workspace lease.
        if (ApplicationManager.getApplication() == null) run.run()
        else ProgressManager.getInstance().executeNonCancelableSection(run)
        return requireNotNull(result)
    }

    private fun executeProcess(root: Path, arguments: List<String>, progress: ((String) -> Unit)?, environment: Map<String, String>): GitCommandResult {
        val readOnly = isReadOnly(arguments)
        val builder = ProcessBuilder(executable + arguments).directory(root.toFile())
        builder.environment().apply {
            putAll(environment)
            // IDEA can inherit these when launched from a shell. Never redirect a repository operation.
            keys.removeIf { it.uppercase() in REPOSITORY_ENVIRONMENT }
            put("GIT_TERMINAL_PROMPT", "0")
            put("GCM_INTERACTIVE", "Never")
            putIfAbsent("GIT_EDITOR", "true")
            putIfAbsent("GIT_SEQUENCE_EDITOR", "true")
            if (readOnly) put("GIT_OPTIONAL_LOCKS", "0")
        }
        val process = try { builder.start() } catch (error: Exception) {
            return GitCommandResult(-1, "", GitDiagnostics.redact(error.message ?: "Git could not be started."))
        }
        val output = Capture()
        val errors = Capture()
        var timedOut = false
        var interrupted = false
        val started = System.nanoTime()
        var lastWarning = started
        var lastOutput = started
        try {
            runCatching { process.outputStream.close() }
            while (process.isAlive) {
                val readOutput = output.drain(process.inputStream)
                val readErrors = errors.drain(process.errorStream)
                val now = System.nanoTime()
                if (readOutput || readErrors) lastOutput = now
                if (readOnly && TimeUnit.NANOSECONDS.toMillis(now - started) >= readTimeoutMillis) {
                    timedOut = true
                    process.destroyForcibly() // Only commands that cannot change refs/work/index are eligible.
                }
                if (!readOnly && TimeUnit.NANOSECONDS.toMillis(now - lastWarning) >= warningMillis) {
                    notify(progress, "Git is still running (process " + process.pid() + "). Waiting for the write to finish safely; Stop takes effect afterwards.")
                    lastWarning = now
                }
                try {
                    if (!readOutput && !readErrors) process.waitFor(
                        if (TimeUnit.NANOSECONDS.toMillis(now - lastOutput) < 100) 1 else 20, TimeUnit.MILLISECONDS,
                    )
                } catch (_: InterruptedException) {
                    interrupted = true // Finish this command before honoring cancellation.
                }
            }
            output.finish(process.inputStream)
            errors.finish(process.errorStream)
            val failure = when {
                timedOut -> "The read-only Git command timed out. No subsequent operation was started."
                output.failed || errors.failed -> "Git finished, but its output could not be captured. Inspect the repository before retrying."
                output.overflow || errors.overflow -> "Git output exceeded the capture limit. Inspect the repository before retrying."
                else -> null
            }
            val result = GitCommandResult(if (failure == null) process.exitValue() else -1, output.text(), failure ?: errors.text())
            if (progress != null) notify(progress, result.diagnostic.trim())
            return result
        } finally {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun notify(progress: ((String) -> Unit)?, message: String) {
        // A disposed/failed UI listener must not abandon a Git write.
        if (message.isNotBlank()) runCatching { progress?.invoke(GitDiagnostics.redact(message)) }
    }

    private class Capture {
        private val bytes = ByteArrayOutputStream()
        private val buffer = ByteArray(8192)
        var overflow = false
            private set
        var failed = false
            private set
        fun drain(stream: InputStream): Boolean {
            if (failed) return false
            return try { readAvailable(stream) } catch (_: java.io.IOException) { failed = true; false }
        }
        private fun readAvailable(stream: InputStream): Boolean {
            var read = false
            // Bounded per poll so a noisy stdout cannot starve stderr or the process timeout.
            repeat(32) {
                val available = stream.available()
                if (available == 0) return read
                val count = stream.read(buffer, 0, minOf(buffer.size, available))
                if (count <= 0) return read
                read = true
                if (bytes.size() + count <= MAX_OUTPUT_BYTES) bytes.write(buffer, 0, count) else overflow = true
            }
            return read
        }
        fun finish(stream: InputStream) {
            // Consume available bytes without waiting on inherited handles in descendants.
            try {
                repeat(64) {
                    if (failed || stream.available() == 0) return
                    drain(stream)
                }
                overflow = true
            } catch (_: java.io.IOException) { failed = true }
        }
        fun text(): String = bytes.toString(Charsets.UTF_8)
    }

    private fun isReadOnly(arguments: List<String>): Boolean {
        var index = 0
        while (index < arguments.size && arguments[index].startsWith('-')) {
            index += if (arguments[index] == "-c") 2 else 1
        }
        val command = arguments.getOrNull(index)
        val rest = arguments.drop(index + 1)
        return command in setOf("rev-parse", "ls-files", "ls-tree", "diff", "rev-list", "show", "show-ref", "for-each-ref", "merge-base", "status") ||
            command == "worktree" && rest.firstOrNull() == "list" ||
            command == "stash" && rest.firstOrNull() == "list" ||
            command == "symbolic-ref" && "--delete" !in rest && "-d" !in rest && rest.count { !it.startsWith('-') } == 1
    }

    private companion object {
        const val MAX_OUTPUT_BYTES = 64 * 1024 * 1024
        val REPOSITORY_ENVIRONMENT = setOf("GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR", "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_PREFIX")
    }
}
