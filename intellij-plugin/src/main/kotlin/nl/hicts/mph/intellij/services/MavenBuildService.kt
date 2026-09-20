package nl.hicts.mph.intellij.services

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Key
import nl.hicts.mph.intellij.model.MavenProjectInfo
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class MavenBuildStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED,
    CANCELLED,
}

data class MavenBuildOptions(
    val goals: List<String> = listOf("clean", "install"),
    val skipUnitTests: Boolean = true,
    val skipIntegrationTests: Boolean = true,
    val parallel: Boolean = false,
    val maxParallel: Int = 1,
    val buildSteps: Map<String, Int> = emptyMap(),
    val prerequisites: Map<String, Set<String>> = emptyMap(),
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

    fun build(
        projects: List<MavenProjectInfo>,
        options: MavenBuildOptions,
        indicator: ProgressIndicator,
        listener: MavenBuildListener,
    ): List<MavenProjectBuildResult> {
        return WorkspaceOperationCoordinator.run("Maven build") {
            executeBuild(projects, options, listener) { stage ->
                if (options.parallel && stage.size > 1) runParallel(stage, options, indicator, listener)
                else stage.map { project -> runOrCancel(project, options, indicator, listener) }
            }
        }
    }

    internal fun executeBuild(
        projects: List<MavenProjectInfo>,
        options: MavenBuildOptions,
        listener: MavenBuildListener,
        runStage: (List<MavenProjectInfo>) -> List<MavenProjectBuildResult>,
    ): List<MavenProjectBuildResult> {
        val results = linkedMapOf<String, MavenProjectBuildResult>()
        val selected = projects.map { it.pomPath }.toSet()
        // Validate the full selected graph before starting any process, including sequential runs.
        val stages = executionStages(projects, options)
        val visited = mutableSetOf<String>()
        stages.forEach { stage ->
            stage.forEach { project ->
                check(options.prerequisites[project.pomPath].orEmpty().filter { it in selected }.all { it in visited }) {
                    "Build prerequisites contain a cycle or an invalid build order. Refresh the build order before running."
                }
            }
            visited += stage.map { it.pomPath }
        }
        stages.forEach { stage ->
            val ready = stage.filter { project ->
                val blocked = options.prerequisites[project.pomPath].orEmpty().any {
                    it in selected && results[it]?.status != MavenBuildStatus.SUCCESS
                }
                if (blocked) {
                    results[project.pomPath] = MavenProjectBuildResult(project, MavenBuildStatus.SKIPPED, null)
                    listener.onEvent(project, MavenBuildStatus.SKIPPED, "Skipping ${project.artifactId}: a prerequisite did not succeed.\n")
                }
                !blocked
            }
            if (ready.isNotEmpty()) runStage(ready).forEach { results[it.project.pomPath] = it }
        }
        return results.values.toList()
    }

    internal fun executionStages(
        projects: List<MavenProjectInfo>,
        options: MavenBuildOptions,
    ): List<List<MavenProjectInfo>> {
        val unique = projects.distinctBy(MavenProjectInfo::pomPath)
        val stages = unique.groupBy { options.buildSteps[it.pomPath] ?: 1 }.toSortedMap().values.toList()
        return if (options.parallel) stages else stages.flatten().map(::listOf)
    }

    private fun runParallel(
        projects: List<MavenProjectInfo>,
        options: MavenBuildOptions,
        indicator: ProgressIndicator,
        listener: MavenBuildListener,
    ): List<MavenProjectBuildResult> {
        val executor = Executors.newFixedThreadPool(options.maxParallel.coerceIn(1, projects.size))
        return try {
            executor.invokeAll(projects.map { project ->
                java.util.concurrent.Callable { runOrCancel(project, options, indicator, listener) }
            }).map { it.get() }
        } finally {
            executor.shutdownNow()
            // Keep the workspace lease until every worker has stopped its process.
            var interrupted = false
            while (!executor.isTerminated) {
                try {
                    executor.awaitTermination(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun runOrCancel(
        project: MavenProjectInfo,
        options: MavenBuildOptions,
        indicator: ProgressIndicator,
        listener: MavenBuildListener,
    ): MavenProjectBuildResult = if (indicator.isCanceled) {
        listener.onEvent(project, MavenBuildStatus.CANCELLED, "Build cancelled before it started.\n")
        MavenProjectBuildResult(project, MavenBuildStatus.CANCELLED, null)
    } else {
        runProject(project, options, indicator, listener)
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
        return try {
            handler.startNotify()
            while (!handler.waitFor(200)) {
                if (indicator.isCanceled || Thread.currentThread().isInterrupted) handler.destroyProcess()
            }
            val exitCode = handler.exitCode
            val status = when {
                indicator.isCanceled -> MavenBuildStatus.CANCELLED
                exitCode == 0 -> MavenBuildStatus.SUCCESS
                else -> MavenBuildStatus.FAILED
            }
            listener.onEvent(project, status, "■ ${project.artifactId}: ${status.name.lowercase()}\n")
            MavenProjectBuildResult(project, status, exitCode)
        } finally {
            if (!handler.isProcessTerminated) {
                handler.destroyProcess()
                val interrupted = Thread.interrupted()
                try {
                    ProgressManager.getInstance().executeNonCancelableSection(Runnable { handler.waitFor() })
                } finally {
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
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
