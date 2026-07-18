package nl.hicts.mph.controllers

import nl.hicts.mph.services.RebaseProgress
import nl.hicts.mph.services.RebaseStartResponse
import nl.hicts.mph.services.RebaseWorkflowService
import nl.hicts.mph.services.SettingsService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux

data class StartRebaseRequest(val rootProjectPaths: List<String>)

@RestController
@RequestMapping("/api/rebase-develop")
class RebaseWorkflowController(
    private val rebaseWorkflowService: RebaseWorkflowService,
    private val settingsService: SettingsService
) {
    @PostMapping("/start")
    fun start(@RequestBody request: StartRebaseRequest): RebaseStartResponse {
        val settings = settingsService.loadSettings()
        val basePath = settings.basePath ?: throw RuntimeException("Base path not set")
        return rebaseWorkflowService.start(basePath, settings.maxScanDepth, request.rootProjectPaths)
    }

    @GetMapping("/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(): Flux<RebaseProgress> = rebaseWorkflowService.events()
}
