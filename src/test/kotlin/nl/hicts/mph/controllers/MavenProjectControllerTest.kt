package nl.hicts.mph.controllers

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import nl.hicts.mph.models.Settings
import nl.hicts.mph.services.BulkVersionUpdate
import nl.hicts.mph.services.ManagedProperty
import nl.hicts.mph.services.MavenProjectService
import nl.hicts.mph.services.ProjectAnalysis
import nl.hicts.mph.services.SbomDetails
import nl.hicts.mph.services.SbomService
import nl.hicts.mph.services.SettingsService
import nl.hicts.mph.services.TagInfo
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import java.nio.file.Files
import java.nio.file.Path

class MavenProjectControllerTest {

    @TempDir
    lateinit var tempDir: Path

    private val projectService = mockk<MavenProjectService>()
    private val settingsService = mockk<SettingsService>()
    private val sbomService = mockk<SbomService>()
    private val controller = MavenProjectController(projectService, settingsService, sbomService)
    private var analyzedProjects: List<ProjectAnalysis> = emptyList()

    @BeforeEach
    fun configureSettings() {
        analyzedProjects = listOf(project("sample", tempDir.resolve("sample/pom.xml").toString()))
        every { settingsService.loadSettings() } returns Settings(tempDir, 5)
        every { projectService.scanAndAnalyze(tempDir, 5) } returns analyzedProjects
    }

    @Test
    fun `analyze should use configured path and depth`() {
        assertEquals(analyzedProjects, controller.analyze())
        verify(exactly = 1) { projectService.scanAndAnalyze(tempDir, 5) }
    }

    @Test
    fun `analyze should reject missing base path`() {
        every { settingsService.loadSettings() } returns Settings(null, 3)

        val exception = assertThrows(RuntimeException::class.java) { controller.analyze() }

        assertEquals("Base path not set", exception.message)
    }

    @Test
    fun `version endpoints should delegate mutations and return refreshed analysis`() {
        justRun { projectService.updateVersions(tempDir, 5, "org.example", "sample", "2.0.0") }
        justRun { projectService.bulkUpdateVersions(tempDir, 5, any()) }

        val updateResult = controller.updateVersion(UpdateVersionRequest("org.example", "sample", "2.0.0"))
        val bulkResult = controller.bulkUpdateVersion(
            BulkUpdateVersionRequest(listOf("/sample/pom.xml"), "feature-", true, "ADD_PREFIX", "feature/test", false)
        )

        assertEquals(analyzedProjects, updateResult)
        assertEquals(analyzedProjects, bulkResult)
        verify(exactly = 1) { projectService.updateVersions(tempDir, 5, "org.example", "sample", "2.0.0") }
        verify(exactly = 1) {
            projectService.bulkUpdateVersions(
                tempDir, 5,
                BulkVersionUpdate(listOf("/sample/pom.xml"), "feature-", true, "ADD_PREFIX", "feature/test", false)
            )
        }
    }

    @Test
    fun `Spring and property endpoints should mutate exact project and refresh`() {
        justRun { projectService.upgradeSpringBoot(tempDir, 5, "/sample/pom.xml", "4.1.1") }
        justRun { projectService.overrideProperty(tempDir, 5, "/sample/pom.xml", "library.version", "2.0", "upgrade") }
        justRun { projectService.removePropertyOverride(tempDir, 5, "/sample/pom.xml", "library.version") }

        assertEquals(analyzedProjects, controller.upgradeSpringBoot(UpgradeSpringBootRequest("/sample/pom.xml", "4.1.1")))
        assertEquals(
            analyzedProjects,
            controller.overrideProperty(OverridePropertyRequest("/sample/pom.xml", "library.version", "2.0", "upgrade"))
        )
        assertEquals(
            analyzedProjects,
            controller.removePropertyOverride(RemovePropertyOverrideRequest("/sample/pom.xml", "library.version"))
        )

        verify { projectService.upgradeSpringBoot(tempDir, 5, "/sample/pom.xml", "4.1.1") }
        verify { projectService.overrideProperty(tempDir, 5, "/sample/pom.xml", "library.version", "2.0", "upgrade") }
        verify { projectService.removePropertyOverride(tempDir, 5, "/sample/pom.xml", "library.version") }
    }

    @Test
    fun `query endpoints should return exact service values`() {
        val properties = listOf(ManagedProperty("library.version", "1.0", null, "Local POM", true))
        val tag = TagInfo("1.2.3", "v1.2.3")
        every { projectService.getManagedProperties(tempDir, 5, "/sample/pom.xml") } returns properties
        every { projectService.getLatestTag(tempDir, 5, "/sample/pom.xml") } returns tag
        every { projectService.getBuildOrder(tempDir, 5) } returns analyzedProjects

        assertEquals(properties, controller.getManagedProperties("/sample/pom.xml"))
        assertEquals(tag, controller.getLatestTag("/sample/pom.xml"))
        assertEquals(analyzedProjects, controller.getBuildOrder())
    }

    @Test
    fun `sync should return messages and refreshed projects`() {
        every { projectService.syncDevelop(tempDir, 5, listOf("/sample/pom.xml"), true) } returns listOf("merged")

        val response = controller.syncDevelop(SyncDevelopRequest(listOf("/sample/pom.xml"), true))

        assertEquals(analyzedProjects, response.projects)
        assertEquals(listOf("merged"), response.messages)
        verify { projectService.syncDevelop(tempDir, 5, listOf("/sample/pom.xml"), true) }
    }

    @Test
    fun `Excel export should include bytes media type and attachment name`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        every { projectService.getBuildOrder(tempDir, 5) } returns analyzedProjects
        every { projectService.exportToExcel(analyzedProjects) } returns bytes

        val response = controller.exportExcel()

        assertEquals(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), response.headers.contentType)
        assertEquals("attachment; filename=build-order.xlsx", response.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION))
        assertArrayEquals(bytes, response.body)
    }

    @Test
    fun `SBOM details should return exact generated representations`() {
        val details = SbomDetails(emptyList(), "<bom/>", "{}")
        every { sbomService.getSbomDetails("/sample/pom.xml") } returns details

        assertEquals(details, controller.getSbomDetails("/sample/pom.xml"))
    }

    @Test
    fun `SBOM export should return selected format and artifact based filename`() {
        val projectPath = tempDir.resolve("sample/pom.xml")
        Files.createDirectories(projectPath.parent)
        Files.writeString(projectPath, "<project/>")
        every { sbomService.generateSbom(projectPath.toString(), "json") } returns "{\"bom\":true}"

        val response = controller.exportSbom(projectPath.toString(), "json")

        assertEquals(MediaType.APPLICATION_JSON, response.headers.contentType)
        assertEquals(true, response.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION)?.matches(Regex("attachment; filename=sample-\\d{12}\\.json")))
        assertArrayEquals("{\"bom\":true}".toByteArray(), response.body)
    }

    private fun project(artifactId: String, path: String) = ProjectAnalysis(
        groupId = "org.example",
        artifactId = artifactId,
        version = "1.0.0",
        path = path,
        modules = emptyList(),
        usages = emptyList(),
        isRoot = true
    )
}
