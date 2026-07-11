package nl.hicts.mph.controllers

import io.mockk.every
import io.mockk.mockk
import nl.hicts.mph.services.NexusIqReportViolation
import nl.hicts.mph.services.NexusIqScanResult
import nl.hicts.mph.services.NexusIqScanSummary
import nl.hicts.mph.services.NexusIqService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class NexusIqControllerTest {

    private val service = mockk<NexusIqService>()
    private val controller = NexusIqController(service)

    @Test
    fun `scan should map complete service result`() {
        val summary = NexusIqScanSummary(critical = 1, severe = 2, moderate = 3, low = 4, total = 10, affectedComponents = 5)
        val violation = NexusIqReportViolation(
            componentIdentifier = "maven:org.example:sample:1.0",
            packageUrl = "pkg:maven/org.example/sample@1.0",
            policyName = "Security-Critical",
            threatLevel = 9,
            reasons = listOf("Test vulnerability"),
            directDependency = true,
            waived = false
        )
        every { service.scan("sample/pom.xml") } returns CompletableFuture.completedFuture(
            NexusIqScanResult("completed", "https://iq.example.org/report/1", summary, listOf(violation))
        )

        val response = controller.scan(NexusIqScanRequest("sample/pom.xml")).get()

        assertEquals("completed", response.message)
        assertEquals("https://iq.example.org/report/1", response.reportUrl)
        assertEquals(summary, response.summary)
        assertEquals(listOf(violation), response.violations)
    }
}
