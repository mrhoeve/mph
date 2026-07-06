package nl.hicts.mph.controllers

import nl.hicts.mph.models.Settings
import nl.hicts.mph.services.MavenProjectService
import nl.hicts.mph.services.ManagedProperty
import nl.hicts.mph.services.ProjectAnalysis
import nl.hicts.mph.services.TagInfo
import nl.hicts.mph.services.SettingsService
import nl.hicts.mph.services.SbomService
import nl.hicts.mph.services.SbomDetails
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import kotlin.io.path.exists

@RestController
class MavenProjectController(
    private val mavenProjectService: MavenProjectService,
    private val settingsService: SettingsService,
    private val sbomService: SbomService
) {

    @GetMapping("/api/projects/analyze")
    fun analyze(): List<ProjectAnalysis> {
        val settings = settingsService.loadSettings()
        val basePath = settings.basePath ?: throw RuntimeException("Base path not set")
        return mavenProjectService.scanAndAnalyze(basePath, settings.maxScanDepth)
    }

    @PostMapping("/api/projects/update-version")
    fun updateVersion(@RequestBody request: UpdateVersionRequest): List<ProjectAnalysis> {
        val settings = settingsService.loadSettings()
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
        val settings = settingsService.loadSettings()
        val basePath = settings.basePath ?: throw RuntimeException("Base path not set")
        mavenProjectService.bulkUpdateVersions(
            basePath,
            settings.maxScanDepth,
            request.rootProjectPaths,
            request.prefix,
            request.updateDependents,
            request.mode ?: "ADD_PREFIX",
            request.branchName,
            request.updateProjects ?: true
        )
        return mavenProjectService.scanAndAnalyze(basePath, settings.maxScanDepth)
    }


    @PostMapping("/api/projects/upgrade-spring-boot")
    fun upgradeSpringBoot(@RequestBody request: UpgradeSpringBootRequest): List<ProjectAnalysis> {
        val settings = settingsService.loadSettings()
        val basePath = settings.basePath ?: throw RuntimeException("Base path not set")
        mavenProjectService.upgradeSpringBoot(basePath, settings.maxScanDepth, request.path, request.newVersion)
        return mavenProjectService.scanAndAnalyze(basePath, settings.maxScanDepth)
    }

    @PostMapping("/api/projects/override-property")
    fun overrideProperty(@RequestBody request: OverridePropertyRequest): List<ProjectAnalysis> {
        val settings = settingsService.loadSettings()
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
        val settings = settingsService.loadSettings()
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
        val settings = settingsService.loadSettings()
        val basePath = settings.basePath ?: throw RuntimeException("Base path not set")
        return mavenProjectService.getManagedProperties(basePath, settings.maxScanDepth, path)
    }

    @GetMapping("/api/projects/latest-tag")
    fun getLatestTag(@RequestParam path: String): TagInfo? {
        return mavenProjectService.getLatestTag(path)
    }

    @GetMapping("/api/projects/build-order")
    fun getBuildOrder(): List<ProjectAnalysis> {
        val settings = settingsService.loadSettings()
        val basePath = settings.basePath ?: throw RuntimeException("Base path not set")
        return mavenProjectService.getBuildOrder(basePath, settings.maxScanDepth)
    }


    @PostMapping("/api/projects/sync-develop")
    fun syncDevelop(@RequestBody request: SyncDevelopRequest): SyncDevelopResponse {
        val settings = settingsService.loadSettings()
        val basePath = settings.basePath ?: throw RuntimeException("Base path not set")
        val messages = mavenProjectService.syncDevelop(request.rootProjectPaths, request.mergeDevelop ?: false)
        val projects = mavenProjectService.scanAndAnalyze(basePath, settings.maxScanDepth)
        return SyncDevelopResponse(projects, messages)
    }

    @GetMapping("/api/projects/export-excel")
    fun exportExcel(): ResponseEntity<ByteArray> {
        val settings = settingsService.loadSettings()
        val basePath = settings.basePath ?: throw RuntimeException("Base path not set")
        val buildOrder = mavenProjectService.getBuildOrder(basePath, settings.maxScanDepth)
        val excelBytes = mavenProjectService.exportToExcel(buildOrder)

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=build-order.xlsx")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(excelBytes)
    }

    @GetMapping("/api/projects/sbom/details")
    fun getSbomDetails(@RequestParam path: String): SbomDetails {
        return sbomService.getSbomDetails(path)
    }

    @GetMapping("/api/projects/sbom/export")
    fun exportSbom(@RequestParam path: String, @RequestParam format: String): ResponseEntity<ByteArray> {
        val sbom = sbomService.generateSbom(path, format)
        val extension = if (format.lowercase() == "xml") "xml" else "json"
        val contentType = if (format.lowercase() == "xml") MediaType.APPLICATION_XML else MediaType.APPLICATION_JSON

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=bom.$extension")
            .contentType(contentType)
            .body(sbom.toByteArray())
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
    val mode: String? = "ADD_PREFIX",
    val branchName: String? = null,
    val updateProjects: Boolean? = true
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

data class SyncDevelopRequest(
    val rootProjectPaths: List<String>,
    val mergeDevelop: Boolean? = false
)

data class SyncDevelopResponse(
    val projects: List<ProjectAnalysis>,
    val messages: List<String>
)
