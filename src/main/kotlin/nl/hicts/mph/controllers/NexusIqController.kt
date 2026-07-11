package nl.hicts.mph.controllers

import nl.hicts.mph.services.NexusIqService
import nl.hicts.mph.services.NexusIqReportViolation
import nl.hicts.mph.services.NexusIqScanSummary
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.CompletableFuture

@RestController
class NexusIqController(
    private val nexusIqService: NexusIqService
) {

    @PostMapping("/api/nexus-iq/scan")
    fun scan(@RequestBody request: NexusIqScanRequest): CompletableFuture<NexusIqScanResponse> {
        return nexusIqService.scan(request.path).thenApply {
            NexusIqScanResponse(it.message, it.reportUrl, it.summary, it.violations)
        }
    }
}

data class NexusIqScanRequest(
    val path: String
)

data class NexusIqScanResponse(
    val message: String,
    val reportUrl: String? = null,
    val summary: NexusIqScanSummary? = null,
    val violations: List<NexusIqReportViolation> = emptyList()
)
