package nl.hicts.mph.controllers

import nl.hicts.mph.services.BuildOptions
import nl.hicts.mph.services.MavenBuildService
import nl.hicts.mph.services.ProjectProgress
import nl.hicts.mph.services.SettingsService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

data class StartBuildRequest(
    val projectPaths: List<String>,
    val options: BuildOptions
)

@RestController
@RequestMapping("/api/builds")
class MavenBuildController(
    private val mavenBuildService: MavenBuildService,
    private val settingsService: SettingsService
) {
    @PostMapping("/start")
    fun startBuild(@RequestBody request: StartBuildRequest) {
        val settings = settingsService.loadSettings()
        val basePath = settings.basePath ?: throw RuntimeException("Base path not set")
        mavenBuildService.startBuild(
            basePath = basePath.toString(),
            maxDepth = settings.maxScanDepth,
            projectPaths = request.projectPaths,
            options = request.options
        )
    }

    @GetMapping("/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun getEvents(): Flux<ProjectProgress> = mavenBuildService.getBuildEvents()

    @PostMapping("/stop")
    fun stopBuild() {
        mavenBuildService.stopBuild()
    }

    @GetMapping("/logs")
    fun getLogs(@RequestParam projectPath: String): List<String> = mavenBuildService.getLogs(projectPath)
}
