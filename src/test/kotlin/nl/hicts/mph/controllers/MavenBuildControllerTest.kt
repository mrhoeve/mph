package nl.hicts.mph.controllers

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import nl.hicts.mph.models.Settings
import nl.hicts.mph.services.BuildOptions
import nl.hicts.mph.services.BuildStatus
import nl.hicts.mph.services.MavenBuildService
import nl.hicts.mph.services.ProjectProgress
import nl.hicts.mph.services.SettingsService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.nio.file.Paths

class MavenBuildControllerTest {

    private val buildService = mockk<MavenBuildService>()
    private val settingsService = mockk<SettingsService>()
    private val controller = MavenBuildController(buildService, settingsService)

    @Test
    fun `start should delegate complete request using configured scan settings`() {
        val options = BuildOptions(skipUTs = false, skipITs = true, parallel = true, maxParallel = 4)
        val paths = listOf("project-a/pom.xml", "project-b/pom.xml")
        every { settingsService.loadSettings() } returns Settings(Paths.get("projects"), 6)
        justRun { buildService.startBuild(any(), any(), any(), any()) }

        controller.startBuild(StartBuildRequest(paths, options))

        verify(exactly = 1) { buildService.startBuild(Paths.get("projects").toString(), 6, paths, options) }
    }

    @Test
    fun `start should fail when base path is not configured`() {
        every { settingsService.loadSettings() } returns Settings(null, 3)

        val exception = assertThrows(RuntimeException::class.java) {
            controller.startBuild(StartBuildRequest(emptyList(), BuildOptions()))
        }

        assertEquals("Base path not set", exception.message)
        verify(exactly = 0) { buildService.startBuild(any(), any(), any(), any()) }
    }

    @Test
    fun `events should expose service event stream unchanged`() {
        val events = listOf(
            ProjectProgress("a/pom.xml", "a", BuildStatus.PENDING),
            ProjectProgress("a/pom.xml", "a", BuildStatus.SUCCESS)
        )
        every { buildService.getBuildEvents() } returns Flux.fromIterable(events)

        assertEquals(events, controller.getEvents().collectList().block())
    }

    @Test
    fun `stop and logs should delegate to service`() {
        justRun { buildService.stopBuild() }
        every { buildService.getLogs("a/pom.xml") } returns listOf("first", "second")

        controller.stopBuild()
        val logs = controller.getLogs("a/pom.xml")

        verify(exactly = 1) { buildService.stopBuild() }
        verify(exactly = 1) { buildService.getLogs("a/pom.xml") }
        assertEquals(listOf("first", "second"), logs)
    }
}
