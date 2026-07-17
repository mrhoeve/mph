package nl.hicts.mph.services

import nl.hicts.mph.logging.LoggerDelegate
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class BuildStatus {
    PENDING, RUNNING, SUCCESS, FAILED, SKIPPED
}

data class BuildLogEntry(
    val projectPath: String,
    val line: String
)

data class ProjectProgress(
    val projectPath: String,
    val artifactId: String,
    val status: BuildStatus,
    val logLine: String? = null
)

data class BuildOptions(
    val skipUTs: Boolean = true,
    val skipITs: Boolean = true,
    val parallel: Boolean = true,
    val maxParallel: Int = 1
)

@Service
class MavenBuildService(
    private val mavenProjectService: MavenProjectService
) {
    private val logger by LoggerDelegate()
    private val buildSink = Sinks.many().multicast().directBestEffort<ProjectProgress>()
    private val projectLogs = ConcurrentHashMap<String, MutableList<String>>()
    private val activeProcesses = ConcurrentHashMap<String, Process>()
    private val isBuilding = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()

    fun getBuildEvents(): Flux<ProjectProgress> = buildSink.asFlux()

    fun getLogs(projectPath: String): List<String> {
        val normalizedPath = Paths.get(projectPath).toAbsolutePath().normalize().toString()
        return projectLogs[normalizedPath] ?: emptyList()
    }

    fun startBuild(basePath: String, maxDepth: Int, projectPaths: List<String>, options: BuildOptions) {
        if (isBuilding.getAndSet(true)) {
            logger.warn("Build already in progress")
            return
        }

        executor.execute {
            try {
                executeBuild(basePath, maxDepth, projectPaths, options)
            } catch (e: Exception) {
                logger.error("Global build failure", e)
                projectPaths.forEach { path ->
                    buildSink.tryEmitNext(ProjectProgress(path, "unknown", BuildStatus.FAILED, "Build failed to start: ${e.message}"))
                }
            } finally {
                isBuilding.set(false)
            }
        }
    }

    private fun executeBuild(basePath: String, maxDepth: Int, projectPathsToBuild: List<String>, options: BuildOptions) {
        val rootProjects = mavenProjectService.scanAndAnalyze(Paths.get(basePath), maxDepth)
        val allProjects = rootProjects.flatMap { flattenAnalysis(it) }
        val requestedPaths = projectPathsToBuild.map(::normalizePath).toSet()
        val projectsToBuild = allProjects.filter { normalizePath(it.path) in requestedPaths }

        if (projectsToBuild.isEmpty()) {
            logger.warn("No projects found to build matching the requested paths")
            return
        }

        projectsToBuild.forEach { project ->
            projectLogs[normalizePath(project.path)] = mutableListOf()
            buildSink.tryEmitNext(ProjectProgress(project.path, project.artifactId, BuildStatus.PENDING))
        }
        runBuildLoop(projectsToBuild, options)
    }

    private fun runBuildLoop(projectsToBuild: List<ProjectAnalysis>, options: BuildOptions) {
        val completed = ConcurrentHashMap<String, BuildStatus>()
        val inProgress = ConcurrentHashMap<String, Boolean>()

        while (completed.size < projectsToBuild.size) {
            val readyToBuild = projectsToBuild.filter { project ->
                !completed.containsKey(project.path) && !inProgress.containsKey(project.path) &&
                    isDependenciesMet(project, projectsToBuild, completed)
            }
            if (readyToBuild.isEmpty() && inProgress.isEmpty()) {
                failBlockedProjects(projectsToBuild, completed)
                return
            }
            if (readyToBuild.isEmpty()) {
                pauseBuildLoop()
                continue
            }

            val toStart = selectProjectsToStart(readyToBuild, inProgress.size, options)
            if (toStart.isEmpty()) {
                pauseBuildLoop()
                continue
            }
            startProjects(toStart, options, completed, inProgress)
            if (!options.parallel) waitForRunningProjects(inProgress)
        }
    }

    private fun failBlockedProjects(
        projects: List<ProjectAnalysis>,
        completed: ConcurrentHashMap<String, BuildStatus>
    ) {
        projects.filterNot { completed.containsKey(it.path) }.forEach { project ->
            completed[project.path] = BuildStatus.FAILED
            buildSink.tryEmitNext(
                ProjectProgress(project.path, project.artifactId, BuildStatus.FAILED, "Dependency cycle or error")
            )
        }
    }

    private fun selectProjectsToStart(
        ready: List<ProjectAnalysis>,
        runningCount: Int,
        options: BuildOptions
    ): List<ProjectAnalysis> {
        if (!options.parallel) return if (runningCount == 0) listOf(ready.first()) else emptyList()
        val capacity = options.maxParallel - runningCount
        return if (capacity > 0) ready.take(capacity) else emptyList()
    }

    private fun startProjects(
        projects: List<ProjectAnalysis>,
        options: BuildOptions,
        completed: ConcurrentHashMap<String, BuildStatus>,
        inProgress: ConcurrentHashMap<String, Boolean>
    ) {
        projects.forEach { project ->
            inProgress[project.path] = true
            executor.execute {
                val status = runMavenCommand(project, options)
                completed[project.path] = status
                inProgress.remove(project.path)
                buildSink.tryEmitNext(ProjectProgress(project.path, project.artifactId, status))
            }
        }
    }

    private fun waitForRunningProjects(inProgress: Map<String, Boolean>) {
        while (inProgress.isNotEmpty()) Thread.sleep(100)
    }

    private fun pauseBuildLoop() {
        Thread.sleep(500)
    }

    private fun normalizePath(path: String): String = Paths.get(path).toAbsolutePath().normalize().toString()

    private fun isDependenciesMet(p: ProjectAnalysis, allToBuild: List<ProjectAnalysis>, completed: Map<String, BuildStatus>): Boolean {
        val deps = findDependencies(p, allToBuild)
        return deps.all { dep -> 
            completed[dep.path] == BuildStatus.SUCCESS 
        }
    }
    
    private fun findDependencies(p: ProjectAnalysis, allProjects: List<ProjectAnalysis>): List<ProjectAnalysis> {
        val pAllPaths = flattenAnalysis(p).map { Paths.get(it.path).toAbsolutePath().normalize().toString() }.toSet()
        
        return allProjects.filter { other -> 
            val normalizedOtherPath = Paths.get(other.path).toAbsolutePath().normalize().toString()
            if (normalizedOtherPath == Paths.get(p.path).toAbsolutePath().normalize().toString()) return@filter false
            
            val otherHierarchy = flattenAnalysis(other)
            otherHierarchy.any { otherModule ->
                otherModule.usages.any { usage ->
                    val usagePath = Paths.get(usage.path).toAbsolutePath().normalize().toString()
                    pAllPaths.contains(usagePath)
                }
            }
        }
    }

    private fun runMavenCommand(p: ProjectAnalysis, options: BuildOptions): BuildStatus {
        val normalizedPath = Paths.get(p.path).toAbsolutePath().normalize().toString()
        val projectDir = File(p.path).parentFile
        val mvnw = findMvnw(projectDir) ?: "mvn"
        
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val args = listOf("install", "-DskipUTs=${options.skipUTs}", "-DskipITs=${options.skipITs}")
        
        val command = if (mvnw.contains("mvnw")) {
            if (isWindows) listOf("cmd.exe", "/c", mvnw) + args
            else listOf(mvnw) + args
        } else {
            if (isWindows) listOf("cmd.exe", "/c", "mvn") + args
            else listOf("mvn") + args
        }

        try {
            buildSink.tryEmitNext(ProjectProgress(p.path, p.artifactId, BuildStatus.RUNNING))
            val process = ProcessBuilder(command)
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()
            
            activeProcesses[normalizedPath] = process
            
            process.inputStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    projectLogs.getOrPut(normalizedPath) { mutableListOf() }.add(line)
                    buildSink.tryEmitNext(ProjectProgress(p.path, p.artifactId, BuildStatus.RUNNING, line))
                }
            }
            
            val exitCode = process.waitFor()
            activeProcesses.remove(normalizedPath)
            return if (exitCode == 0) BuildStatus.SUCCESS else BuildStatus.FAILED
        } catch (e: Exception) {
            logger.error("Failed to run Maven build for ${p.artifactId}", e)
            projectLogs.getOrPut(normalizedPath) { mutableListOf() }.add("Error: ${e.message}")
            return BuildStatus.FAILED
        }
    }

    private fun findMvnw(dir: File): String? {
        var current: File? = dir
        for (i in 0..5) {
            if (current == null) break
            val mvnwFileName = if (System.getProperty("os.name").lowercase().contains("win")) "mvnw.cmd" else "mvnw"
            val mvnw = File(current, mvnwFileName)
            if (mvnw.exists()) return mvnw.absolutePath
            current = current.parentFile
        }
        return null
    }

    private fun flattenAnalysis(project: ProjectAnalysis): List<ProjectAnalysis> {
        return listOf(project) + project.modules.flatMap { flattenAnalysis(it) }
    }

    fun stopBuild() {
        activeProcesses.values.forEach { process ->
            process.destroy()
            // Try to kill more aggressively if needed
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
        activeProcesses.clear()
        isBuilding.set(false)
    }
}
