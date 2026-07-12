package nl.hicts.mph.controllers

import io.mockk.every
import io.mockk.mockk
import nl.hicts.mph.models.Settings
import nl.hicts.mph.services.SettingsService
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.test.web.reactive.server.WebTestClient
import java.nio.file.Path

class FileSystemControllerIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `current endpoint should never serialize Nexus IQ secret`() {
        val settingsService = mockk<SettingsService>()
        every { settingsService.loadSettings() } returns Settings(
            basePath = tempDir,
            maxScanDepth = 3,
            nexusIqUser = "test-user",
            nexusIqPass = "test-secret"
        )
        val client = WebTestClient.bindToController(FileSystemController(settingsService)).build()

        client.get().uri("/api/filesystem/current")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.nexusIqUser").isEqualTo("test-user")
            .jsonPath("$.nexusIqPass").doesNotExist()
    }
}
