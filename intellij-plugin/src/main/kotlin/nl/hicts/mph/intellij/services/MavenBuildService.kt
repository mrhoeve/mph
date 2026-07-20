package nl.hicts.mph.intellij.services

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Key
import nl.hicts.mph.intellij.model.MavenProjectInfo
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class MavenBuildStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED,
}

data class MavenBuildOptions(
    val goals: List<String> = listOf("clean", "install"),
    val skipUnitTests: Boolean = true,
    val skipIntegrationTests: Boolean = true,
)

data class MavenProjectBuildResult(
    val project: MavenProjectInfo,
    val status: MavenBuildStatus,
    val exitCode: Int?,
)

fun interface MavenBuildListener {
    fun onEvent(project: MavenProjectInfo, status: MavenBuildStatus, text: String?)
}

@Service(Service.Level.PROJECT)
class MavenBuildService {
    private val activeHandlers = ConcurrentHashMap.newKeySet<OSProcessHandler>()
    private val cancelRequested = AtomicBoolean(false)

    fun build(
        projects: List<MavenProjectInfo>,
        options: MavenBuildOptions,
        indicator: ProgressIndicator,
        listener: MavenBuildListener,
    ): List<MavenProjectBuildResult> {
        cancelRequested.set(false)
        return try {
            projects.distinctBy(MavenProjectInfo::pomPath).map { project ->
                if (indicator.isCanceled || cancelRequested.get()) {
                    listener.onEvent(project, MavenBuildStatus.CANCELLED, "Build cancelled before it started.\n")
                    MavenProjectBuildResult(project, MavenBuildStatus.CANCELLED, null)
                } else {
                    runProject(project, options, indicator, listener)
                }
            }
        } finally {
            cancelRequested.set(false)
        }
    }

    fun cancel() {
        cancelRequested.set(true)
        activeHandlers.forEach(OSProcessHandler::destroyProcess)
    }

    internal fun commandLine(
        project: MavenProjectInfo,
        options: MavenBuildOptions,
        osName: String = System.getProperty("os.name"),
    ): GeneralCommandLine {
        require(options.goals.isNotEmpty()) { "Enter at least one Maven goal." }
        val projectDirectory = Path.of(project.pomPath).toAbsolutePath().normalize().parent
        val windows = osName.lowercase().contains("win")
        val wrapper = findWrapper(projectDirectory, windows)
        val arguments = buildList {
            add("--batch-mode")
            addAll(options.goals)
            if (options.skipUnitTests) add("-DskipTests=true")
            if (options.skipIntegrationTests) add("-DskipITs=true")
        }

        val command = when {
            windows -> GeneralCommandLine("cmd.exe").withParameters(
                buildList {
                    add("/d")
                    add("/c")
                    add(wrapper?.toString() ?: "mvn")
                    addAll(arguments)
                },
            )
            wrapper != null -> GeneralCommandLine("sh").withParameters(
                buildList {
                    add(wrapper.toString())
                    addAll(arguments)
                },
            )
            else -> GeneralCommandLine("mvn").withParameters(arguments)
        }
        return command.withWorkDirectory(projectDirectory.toFile()).apply {
            charset = StandardCharsets.UTF_8
        }
    }

    private fun runProject(
        project: MavenProjectInfo,
        options: MavenBuildOptions,
        indicator: ProgressIndicator,
        listener: MavenBuildListener,
    ): MavenProjectBuildResult {
        listener.onEvent(project, MavenBuildStatus.RUNNING, "\n▶ Building ${project.artifactId}\n")
        val handler = try {
            OSProcessHandler(commandLine(project, options))
        } catch (error: Exception) {
            listener.onEvent(
                project,
                MavenBuildStatus.FAILED,
                "Unable to start Maven: ${error.message ?: error.javaClass.simpleName}\n",
            )
            return MavenProjectBuildResult(project, MavenBuildStatus.FAILED, null)
        }
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = if (outputType == ProcessOutputTypes.STDERR) "[error] ${event.text}" else event.text
                listener.onEvent(project, MavenBuildStatus.RUNNING, text)
            }
        })
        activeHandlers += handler
        return try {
            handler.startNotify()
            while (!handler.waitFor(200)) {
                if (indicator.isCanceled || cancelRequested.get()) handler.destroyProcess()
            }
            val exitCode = handler.exitCode
            val status = when {
                indicator.isCanceled || cancelRequested.get() -> MavenBuildStatus.CANCELLED
                exitCode == 0 -> MavenBuildStatus.SUCCESS
                else -> MavenBuildStatus.FAILED
            }
            listener.onEvent(project, status, "■ ${project.artifactId}: ${status.name.lowercase()}\n")
            MavenProjectBuildResult(project, status, exitCode)
        } finally {
            activeHandlers -= handler
        }
    }

    private fun findWrapper(start: Path, windows: Boolean): Path? {
        val wrapperName = if (windows) "mvnw.cmd" else "mvnw"
        var directory: Path? = start
        repeat(MAX_WRAPPER_PARENT_DEPTH + 1) {
            val current = directory ?: return null
            val candidate = current.resolve(wrapperName)
            if (Files.isRegularFile(candidate)) return candidate
            directory = current.parent
        }
        return null
    }

    private companion object {
        const val MAX_WRAPPER_PARENT_DEPTH = 5
    }
}
