package nl.hicts.mph.services

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import nl.hicts.mph.models.Settings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture

class NexusIqServiceTest {

    private val settingsService = mockk<SettingsService>()
    private val mavenCommandService = mockk<MavenCommandService>()
    private val service = NexusIqService(settingsService, mavenCommandService)

    @Test
    fun `should construct correct scan command`() {
        val projectPath = Paths.get("src/test/resources/test-data/a-project").toAbsolutePath().toString()
        val settings = Settings(
            basePath = Paths.get("src/test/resources/test-data"),
            maxScanDepth = 3,
            nexusIqUrl = "https://iq.example.com",
            nexusIqUser = "admin",
            nexusIqPass = "admin123",
            nexusIqAppIdPrefix = "test-prefix:"
        )
        
        every { settingsService.loadSettings() } returns settings
        
        val argsSlot = slot<List<String>>()
        every { 
            mavenCommandService.runMavenCommandInBackground(any(), capture(argsSlot), any()) 
        } returns CompletableFuture.completedFuture(0)

        service.scan(projectPath).get()

        val args = argsSlot.captured
        assertTrue(args.contains("com.sonatype.clm:clm-maven-plugin:evaluate"), "Should use clm-maven-plugin")
        assertTrue(args.contains("-Dclm.serverUrl=https://iq.example.com"), "Should include server URL")
        assertTrue(args.contains("-Dclm.applicationId=test-prefix:a-project"), "Should include prefixed application ID")
        assertTrue(args.contains("-Dclm.username=admin"), "Should include username")
        assertTrue(args.contains("-Dclm.password=admin123"), "Should include password")
    }

    @Test
    fun `should return empty list when no server configured`() {
        val settings = Settings(
            basePath = Paths.get("src/test/resources/test-data"),
            maxScanDepth = 3,
            nexusIqUrl = null
        )
        every { settingsService.loadSettings() } returns settings
        
        val violations = service.getVulnerabilities("org.apache.logging.log4j", "log4j-core", "2.14.1")
        
        assertTrue(violations.isEmpty(), "Should return empty violations when no server is configured")
    }
}
