package nl.hicts.mph.controllers

import nl.hicts.mph.services.MavenProjectService
import nl.hicts.mph.services.ProjectAnalysis
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import kotlin.io.path.exists

@RestController
class MavenProjectController(
    private val mavenProjectService: MavenProjectService
) {
    private val settingsDirectory: Path = Paths.get(System.getProperty("user.home"), ".mph")
    private val settingsFile: Path = settingsDirectory.resolve("settings.properties")

    @GetMapping("/api/projects/analyze")
    fun analyze(): List<ProjectAnalysis> {
        val settings = loadSettings()
        val basePath = settings.basePath ?: throw RuntimeException("Base path not set")
        return mavenProjectService.scanAndAnalyze(basePath, settings.maxScanDepth)
    }

    @PostMapping("/api/projects/update-version")
    fun updateVersion(@RequestBody request: UpdateVersionRequest): List<ProjectAnalysis> {
        val settings = loadSettings()
        val basePath = settings.basePath ?: throw RuntimeException("Base path not set")
        mavenProjectService.updateVersions(
            basePath, 
            settings.maxScanDepth, 
            request.groupId, 
            request.artifactId, 
            request.newVersion
        )
        return mavenProjectService.scanAndAnalyze(basePath, settings.maxScanDepth)
    }

    @PostMapping("/api/projects/bulk-update-version")
    fun bulkUpdateVersion(@RequestBody request: BulkUpdateVersionRequest): List<ProjectAnalysis> {
        val settings = loadSettings()
        val basePath = settings.basePath ?: throw RuntimeException("Base path not set")
        mavenProjectService.bulkUpdateVersions(
            basePath,
            settings.maxScanDepth,
            request.rootProjectPaths,
            request.prefix,
            request.updateDependents,
            request.mode ?: "ADD_PREFIX"
        )
        return mavenProjectService.scanAndAnalyze(basePath, settings.maxScanDepth)
    }

    private fun loadSettings(): Settings {
        if (!settingsFile.exists()) {
            return Settings(null, 3)
        }

        val properties = Properties()

        Files.newInputStream(settingsFile).use { inputStream ->
            properties.load(inputStream)
        }

        val basePathStr = properties.getProperty("basePath")
            ?.takeIf { it.isNotBlank() }
        
        val maxScanDepth = properties.getProperty("maxScanDepth")?.toIntOrNull() ?: 3

        val path = basePathStr?.let { Paths.get(it) }

        return Settings(path, maxScanDepth)
    }
}

data class UpdateVersionRequest(
    val groupId: String,
    val artifactId: String,
    val newVersion: String
)

data class BulkUpdateVersionRequest(
    val rootProjectPaths: List<String>,
    val prefix: String,
    val updateDependents: Boolean,
    val mode: String? = "ADD_PREFIX"
)
