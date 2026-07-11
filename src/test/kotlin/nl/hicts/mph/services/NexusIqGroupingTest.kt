package nl.hicts.mph.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.mockk.every
import io.mockk.mockk
import nl.hicts.mph.models.Settings
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import java.util.concurrent.CompletableFuture

class NexusIqGroupingTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `should group multiple violations for the same component`() {
        val settingsService = mockk<SettingsService>()
        val mavenCommandService = mockk<MavenCommandService>()

        val projectDir = tempDir.resolve("grouped-project")
        Files.createDirectories(projectDir)
        Files.writeString(projectDir.resolve("pom.xml"), "<project><artifactId>grouped-project</artifactId></project>")
        Files.writeString(projectDir.resolve("Jenkinsfile"), "servicePipeline('grouped-project-svc', false, '21')")

        val settings = Settings(
            basePath = tempDir,
            maxScanDepth = 3,
            nexusIqUrl = "https://iq.example.com"
        )
        every { settingsService.loadSettings() } returns settings
        every { mavenCommandService.runMavenCommandInBackground(any(), any(), any(), any()) } returns CompletableFuture.completedFuture(0)

        val webClientBuilder = WebClient.builder().exchangeFunction { request ->
            val body = when {
                request.url().path.endsWith("/api/v2/applications") ->
                    """{"applications":[{"id":"internal-id","publicId":"grouped-project-svc"}]}"""
                request.url().path.contains("/api/v2/reports/applications/") ->
                    """[{"stage":"build","reportHtmlUrl":"ui/links/application/grouped-project-svc/report/report-id","evaluationDate":"2026-07-11T10:15:30Z"}]"""
                else ->
                    """{
                        "components":[
                            {
                                "componentIdentifier":{"format":"maven","coordinates":{"groupId":"org.example","artifactId":"lib-a","version":"1.0"}},
                                "displayName":"org.example : lib-a : 1.0",
                                "violations":[
                                    {
                                        "policyName":"Critical-Policy",
                                        "policyThreatLevel":9,
                                        "waived":false,
                                        "constraints":[{"conditions":[{"conditionReason":"Reason 1"}]}]
                                    },
                                    {
                                        "policyName":"Severe-Policy",
                                        "policyThreatLevel":6,
                                        "waived":true,
                                        "constraints":[{"conditions":[{"conditionReason":"Reason 2"}]}]
                                    }
                                ]
                            }
                        ]
                    }"""
            }
            Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body).build())
        }

        val service = NexusIqService(settingsService, mavenCommandService, webClientBuilder)
        val result = service.scan(projectDir.toString()).get()

        assertEquals(1, result.violations.size)
        val violation = result.violations[0]
        assertEquals("org.example : lib-a : 1.0", violation.componentIdentifier)
        assertEquals(9, violation.threatLevel) // Highest
        assertEquals("Critical-Policy", violation.policyName) // From highest
        assertTrue(violation.waived) // One is waived
        assertEquals(2, violation.details.size)
        assertEquals("Critical-Policy", violation.details[0].policyName)
        assertEquals("Severe-Policy", violation.details[1].policyName)
        assertEquals(listOf("Reason 1", "Reason 2"), violation.reasons.sorted())

        // Verify summary totals reflect individual violations, not grouped ones
        val summary = result.summary!!
        assertEquals(1, summary.critical) // Critical-Policy (9)
        assertEquals(1, summary.severe)   // Severe-Policy (6)
        assertEquals(2, summary.total)    // 1 critical + 1 severe
        assertEquals(1, summary.affectedComponents)
    }
}
