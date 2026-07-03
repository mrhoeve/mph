package nl.hicts.mph.services

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import nl.hicts.mph.models.Settings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture

class NexusIqServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val settingsService = mockk<SettingsService>()
    private val mavenCommandService = mockk<MavenCommandService>()
    private val service = NexusIqService(settingsService, mavenCommandService)

    @Test
    fun `should construct correct scan command`() {
        val projectDir = tempDir.resolve("a-project")
        Files.createDirectories(projectDir)
        Files.writeString(projectDir.resolve("pom.xml"), "<project><artifactId>a-project</artifactId></project>")
        Files.writeString(projectDir.resolve("Jenkinsfile"), "servicePipeline('a-project-svc', false, '21')")
        
        val settings = Settings(
            basePath = tempDir,
            maxScanDepth = 3,
            nexusIqUrl = "https://iq.example.com",
            nexusIqUser = "admin",
            nexusIqPass = "admin123",
            nexusIqAppIdPrefix = "test-prefix:",
            nexusIqAppIdSuffix = "-test"
        )
        
        every { settingsService.loadSettings() } returns settings
        
        val argsSlot = slot<List<String>>()
        every { 
            mavenCommandService.runMavenCommandInBackground(any(), capture(argsSlot), any()) 
        } returns CompletableFuture.completedFuture(0)

        val result = service.scan(projectDir.toString()).get()
        assertEquals("Scan completed successfully for $projectDir", result.message)
        assertEquals("https://iq.example.com/ui/links/application/test-prefix:a-project-svc-test/report/latest", result.reportUrl)

        val args = argsSlot.captured
        assertTrue(args.contains("com.sonatype.clm:clm-maven-plugin:evaluate"), "Should use clm-maven-plugin")
        assertTrue(args.contains("-Dclm.serverUrl=https://iq.example.com"), "Should include server URL")
        assertTrue(args.contains("-Dclm.applicationId=test-prefix:a-project-svc-test"), "Should include correctly constructed application ID")
        assertTrue(args.contains("-Dclm.username=admin"), "Should include username")
        assertTrue(args.contains("-Dclm.password=admin123"), "Should include password")
    }

    @Test
    fun `should handle scan when path points to pom_xml file`() {
        val projectDir = tempDir.resolve("b-project")
        Files.createDirectories(projectDir)
        val pomFile = projectDir.resolve("pom.xml")
        Files.writeString(pomFile, "<project><artifactId>b-project</artifactId></project>")
        Files.writeString(projectDir.resolve("Jenkinsfile"), "servicePipeline('b-project-svc')")
        
        val settings = Settings(
            basePath = tempDir,
            maxScanDepth = 3,
            nexusIqUrl = "https://iq.example.com"
        )
        
        every { settingsService.loadSettings() } returns settings
        
        every { 
            mavenCommandService.runMavenCommandInBackground(any(), any(), any()) 
        } returns CompletableFuture.completedFuture(0)

        val result = service.scan(pomFile.toString()).get()
        assertEquals("Scan completed successfully for ${pomFile.toString()}", result.message)
        assertEquals("https://iq.example.com/ui/links/application/b-project-svc/report/latest", result.reportUrl)
        
        // Verify that the command was run in projectDir, not in pomFile path
        verify { 
            mavenCommandService.runMavenCommandInBackground(projectDir.toFile(), any(), any()) 
        }
    }

    @Test
    fun `should extract App ID even if path points to pom_xml`() {
        val projectDir = tempDir.resolve("c-project")
        Files.createDirectories(projectDir)
        val pomFile = projectDir.resolve("pom.xml")
        Files.writeString(pomFile, "<project/>")
        Files.writeString(projectDir.resolve("Jenkinsfile"), "servicePipeline('c-service')")
        
        val settings = Settings(
            basePath = tempDir,
            maxScanDepth = 3
        )
        
        val appId = service.extractNexusIqAppId(pomFile.toString(), settings)
        assertEquals("c-service", appId)
    }

    @Test
    fun `should extract App ID from Jenkinsfile`() {
        val projectDir = tempDir.resolve("j-project")
        Files.createDirectories(projectDir)
        val jenkinsfile = projectDir.resolve("Jenkinsfile")
        Files.writeString(jenkinsfile, "servicePipeline('my-service', false, '21')")
        
        val settings = Settings(
            basePath = tempDir,
            maxScanDepth = 3,
            nexusIqAppIdPrefix = "pre-",
            nexusIqAppIdSuffix = "-suf"
        )
        
        val appId = service.extractNexusIqAppId(projectDir.toString(), settings)
        assertEquals("pre-my-service-suf", appId)
        
        // Test libraryPipeline
        Files.writeString(jenkinsfile, "libraryPipeline('my-lib', true)")
        val appIdLib = service.extractNexusIqAppId(projectDir.toString(), settings)
        assertEquals("pre-my-lib-suf", appIdLib)
    }

    @Test
    fun `should return null if no Jenkinsfile or no pipeline call`() {
        val projectDir = tempDir.resolve("no-j-project")
        Files.createDirectories(projectDir)
        
        val settings = Settings(
            basePath = tempDir,
            maxScanDepth = 3
        )
        
        val appId = service.extractNexusIqAppId(projectDir.toString(), settings)
        assertEquals(null, appId)
        
        Files.writeString(projectDir.resolve("Jenkinsfile"), "some other content")
        val appIdEmpty = service.extractNexusIqAppId(projectDir.toString(), settings)
        assertEquals(null, appIdEmpty)
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

    @Test
    fun `should construct correct report URL`() {
        val settings = Settings(
            basePath = tempDir,
            maxScanDepth = 3,
            nexusIqUrl = "https://iq.example.com/"
        )
        
        val url = service.getReportUrl("my-app", settings)
        assertEquals("https://iq.example.com/ui/links/application/my-app/report/latest", url)
        
        val urlNoSlash = service.getReportUrl("my-app", settings.copy(nexusIqUrl = "https://iq.example.com"))
        assertEquals("https://iq.example.com/ui/links/application/my-app/report/latest", urlNoSlash)
    }
}
