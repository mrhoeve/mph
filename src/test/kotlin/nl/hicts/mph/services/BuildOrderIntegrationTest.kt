package nl.hicts.mph.services

import io.mockk.every
import io.mockk.mockk
import nl.hicts.mph.models.Settings
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class BuildOrderIntegrationTest {

    private val mavenCommandService = mockk<MavenCommandService>()
    private val gitService = mockk<GitService>()
    private val nexusIqService = mockk<NexusIqService>()
    private val settingsService = mockk<SettingsService>()
    private val sbomService = mockk<SbomService>()
    private val service = MavenProjectService(mavenCommandService, gitService, nexusIqService, settingsService, sbomService)

    @Test
    fun `should only contain root projects and have correct order`() {
        every { settingsService.loadSettings() } returns Settings(
            basePath = Paths.get("src/test/resources/test-data"),
            maxScanDepth = 3
        )
        every { gitService.getLatestTagInfo(any()) } returns null
        every { gitService.getGitStatus(any()) } returns GitStatus("main", 0, 0)
        every { nexusIqService.extractNexusIqAppId(any(), any()) } returns null
        io.mockk.justRun { gitService.clearCache() }
        io.mockk.justRun { sbomService.setWorkspace(any()) }
        val testDataPath = Paths.get("src/test/resources/test-data")
        val buildOrder = service.getBuildOrder(testDataPath, 3)

        // 1. Verify only root projects are present
        assertTrue(buildOrder.isNotEmpty(), "Build order should not be empty")
        assertTrue(buildOrder.all { it.isRoot }, "Expected only root projects in the build order list")

        // 2. Verify specific dependency ordering
        // In our test data, a-project-service uses multi-module-project's service-parent as parent.
        // Therefore, multi-module-project must appear before a-project.

        val aProjectIndex = buildOrder.indexOfFirst { it.artifactId == "a-project" }
        val multiModuleProjectIndex = buildOrder.indexOfFirst { it.artifactId == "multi-module-project" }

        assertTrue(aProjectIndex != -1, "a-project should be in the list")
        assertTrue(multiModuleProjectIndex != -1, "multi-module-project should be in the list")
        assertTrue(multiModuleProjectIndex < aProjectIndex, "multi-module-project should come before a-project because of dependency")

        println("Verified Build Order:")
        buildOrder.forEachIndexed { index, project ->
            println("${index + 1}. ${project.artifactId}")
        }
    }
}
