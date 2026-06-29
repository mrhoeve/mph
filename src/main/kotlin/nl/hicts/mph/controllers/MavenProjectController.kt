package nl.hicts.mph.controllers

import nl.hicts.mph.services.MavenProjectService
import nl.hicts.mph.services.ManagedProperty
import nl.hicts.mph.services.ProjectAnalysis
import nl.hicts.mph.services.SpringBootUpgradeSuggestions
import nl.hicts.mph.services.SpringBootVersionService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import kotlin.io.path.exists

@RestController
class MavenProjectController(
    private val mavenProjectService: MavenProjectService,
    private val springBootVersionService: SpringBootVersionService
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

    @GetMapping("/api/projects/spring-boot-suggestions")
    fun getSpringBootSuggestions(@RequestParam currentVersion: String): Mono<SpringBootUpgradeSuggestions> {
        return springBootVersionService.getSuggestions(currentVersion)
    }

    @PostMapping("/api/projects/upgrade-spring-boot")
    fun upgradeSpringBoot(@RequestBody request: UpgradeSpringBootRequest): List<ProjectAnalysis> {
        val settings = loadSettings()
        val basePath = settings.basePath ?: throw RuntimeException("Base path not set")
        mavenProjectService.upgradeSpringBoot(basePath, settings.maxScanDepth, request.path, request.newVersion)
        return mavenProjectService.scanAndAnalyze(basePath, settings.maxScanDepth)
    }

    @PostMapping("/api/projects/override-property")
    fun overrideProperty(@RequestBody request: OverridePropertyRequest): List<ProjectAnalysis> {
        val settings = loadSettings()
        val basePath = settings.basePath ?: throw RuntimeException("Base path not set")
        mavenProjectService.overrideProperty(
            basePath,
            settings.maxScanDepth,
            request.path,
            request.propertyName,
            request.newValue,
            request.remark
        )
        return mavenProjectService.scanAndAnalyze(basePath, settings.maxScanDepth)
    }

    @PostMapping("/api/projects/remove-property-override")
    fun removePropertyOverride(@RequestBody request: RemovePropertyOverrideRequest): List<ProjectAnalysis> {
        val settings = loadSettings()
        val basePath = settings.basePath ?: throw RuntimeException("Base path not set")
        mavenProjectService.removePropertyOverride(
            basePath,
            settings.maxScanDepth,
            request.path,
            request.propertyName
        )
        return mavenProjectService.scanAndAnalyze(basePath, settings.maxScanDepth)
    }

    @GetMapping("/api/projects/managed-properties")
    fun getManagedProperties(@RequestParam path: String): List<ManagedProperty> {
        val settings = loadSettings()
        val basePath = settings.basePath ?: throw RuntimeException("Base path not set")
        return mavenProjectService.getManagedProperties(basePath, settings.maxScanDepth, path)
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

data class UpgradeSpringBootRequest(
    val path: String,
    val newVersion: String
)

data class OverridePropertyRequest(
    val path: String,
    val propertyName: String,
    val newValue: String,
    val remark: String?
)

data class RemovePropertyOverrideRequest(
    val path: String,
    val propertyName: String
)
