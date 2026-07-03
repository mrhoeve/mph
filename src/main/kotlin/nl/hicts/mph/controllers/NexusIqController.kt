package nl.hicts.mph.controllers

import nl.hicts.mph.services.NexusIqService
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CompletableFuture

@RestController
class NexusIqController(
    private val nexusIqService: NexusIqService
) {

    @PostMapping("/api/nexus-iq/scan")
    fun scan(@RequestBody request: NexusIqScanRequest): CompletableFuture<String> {
        return nexusIqService.scan(request.path)
    }
}

data class NexusIqScanRequest(
    val path: String
)
