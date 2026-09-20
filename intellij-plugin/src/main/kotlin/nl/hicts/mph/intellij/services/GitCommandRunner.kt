package nl.hicts.mph.intellij.services

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import java.nio.charset.StandardCharsets
import java.nio.file.Path

internal data class GitCommandResult(val exitCode: Int, val output: String, val stderr: String = "") {
    val diagnostic: String get() = listOf(output, stderr).filter { it.isNotBlank() }.joinToString("\n")
}

internal fun interface GitCommandRunner {
    fun execute(root: Path, arguments: List<String>, progress: ((String) -> Unit)?, environment: Map<String, String>): GitCommandResult
}

internal class NativeGitCommandRunner : GitCommandRunner {
    override fun execute(
        root: Path,
        arguments: List<String>,
        progress: ((String) -> Unit)?,
        environment: Map<String, String>,
    ): GitCommandResult {
        val output = StringBuilder()
        val errors = StringBuilder()
        val command = GeneralCommandLine("git")
            .withParameters(arguments)
            .withWorkDirectory(root.toFile())
            .withEnvironment(mapOf("GIT_EDITOR" to "true", "GIT_SEQUENCE_EDITOR" to "true") + environment)
        command.charset = StandardCharsets.UTF_8
        val handler = try {
            OSProcessHandler(command)
        } catch (error: Exception) {
            return GitCommandResult(-1, "", error.message ?: error.javaClass.simpleName)
        }
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType != ProcessOutputTypes.STDOUT && outputType != ProcessOutputTypes.STDERR) return
                val target = if (outputType == ProcessOutputTypes.STDOUT) output else errors
                synchronized(target) { target.append(event.text) }
                progress?.invoke(event.text.trimEnd())
            }
        })
        return run {
            handler.startNotify()
            // A stop request takes effect between commands. Never kill a stash/rebase/index write.
            if (ApplicationManager.getApplication() == null) {
                handler.waitFor()
            } else {
                ProgressManager.getInstance().executeNonCancelableSection(Runnable { handler.waitFor() })
            }
            val exitCode = handler.exitCode ?: -1
            GitCommandResult(exitCode, synchronized(output) { output.toString() }, synchronized(errors) { errors.toString() })
        }
    }

}
