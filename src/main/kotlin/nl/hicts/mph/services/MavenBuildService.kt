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
    val parallel: Boolean = true
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
        
    val projectsToBuild = allProjects.filter { p -> 
            val normalizedPPath = Paths.get(p.path).toAbsolutePath().normalize().toString()
            projectPathsToBuild.any { 
                Paths.get(it).toAbsolutePath().normalize().toString() == normalizedPPath 
            } 
        }
        
        if (projectsToBuild.isEmpty()) {
            logger.warn("No projects found to build matching the requested paths")
            return
        }
        
        // Initial status update
        projectsToBuild.forEach { p ->
            val normalizedPath = Paths.get(p.path).toAbsolutePath().normalize().toString()
            projectLogs[normalizedPath] = mutableListOf()
            buildSink.tryEmitNext(ProjectProgress(p.path, p.artifactId, BuildStatus.PENDING))
        }

        val completed = ConcurrentHashMap<String, BuildStatus>()
        val inProgress = ConcurrentHashMap<String, Boolean>()

        // Recursive build function or loop
        while (completed.size < projectsToBuild.size) {
            val readyToBuild = projectsToBuild.filter { p ->
                !completed.containsKey(p.path) && !inProgress.containsKey(p.path) &&
                isDependenciesMet(p, projectsToBuild, completed)
            }

            if (readyToBuild.isEmpty() && inProgress.isEmpty()) {
                // Should not happen if no cycles
                projectsToBuild.filter { !completed.containsKey(it.path) }.forEach { 
                    completed[it.path] = BuildStatus.FAILED
                    buildSink.tryEmitNext(ProjectProgress(it.path, it.artifactId, BuildStatus.FAILED, "Dependency cycle or error"))
                }
                break
            }

            if (readyToBuild.isEmpty()) {
                Thread.sleep(500)
                continue
            }

            val toStart = if (options.parallel) readyToBuild else listOf(readyToBuild.first())

            toStart.forEach { p ->
                inProgress[p.path] = true
                executor.execute {
                    val status = runMavenCommand(p, options)
                    completed[p.path] = status
                    inProgress.remove(p.path)
                    buildSink.tryEmitNext(ProjectProgress(p.path, p.artifactId, status))
                }
            }
            
            if (!options.parallel) {
                // Wait for the single build to finish
                while (inProgress.isNotEmpty()) {
                    Thread.sleep(100)
                }
            }
        }
    }

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
        val args = mutableListOf("install", "-DskipUTs=${options.skipUTs}", "-DskipITs=${options.skipITs}")
        
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
        activeProcesses.forEach { (path, process) ->
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
