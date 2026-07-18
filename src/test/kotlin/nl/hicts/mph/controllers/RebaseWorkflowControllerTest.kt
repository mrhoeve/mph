package nl.hicts.mph.controllers

import io.mockk.every
import io.mockk.mockk
import nl.hicts.mph.models.Settings
import nl.hicts.mph.services.RebaseProgress
import nl.hicts.mph.services.RebaseProgressStatus
import nl.hicts.mph.services.RebaseStartResponse
import nl.hicts.mph.services.RebaseWorkflowService
import nl.hicts.mph.services.SettingsService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reactor.core.publisher.Flux
import java.nio.file.Path

class RebaseWorkflowControllerTest {

    @TempDir
    lateinit var tempDir: Path

    private val workflow = mockk<RebaseWorkflowService>()
    private val settingsService = mockk<SettingsService>()
    private val controller = RebaseWorkflowController(workflow, settingsService)

    @Test
    fun `should start with configured workspace and expose events`() {
        val response = RebaseStartResponse("TEST-", emptyList())
        val event = RebaseProgress(status = RebaseProgressStatus.COMPLETED, message = "done", overall = true)
        every { settingsService.loadSettings() } returns Settings(tempDir, 4)
        every { workflow.start(tempDir, 4, listOf("project/pom.xml")) } returns response
        every { workflow.events() } returns Flux.just(event)

        assertEquals(response, controller.start(StartRebaseRequest(listOf("project/pom.xml"))))
        assertEquals(event, controller.events().blockFirst())
    }

    @Test
    fun `should reject start when base path is missing`() {
        every { settingsService.loadSettings() } returns Settings(null, 3)

        val error = assertThrows(RuntimeException::class.java) {
            controller.start(StartRebaseRequest(listOf("project/pom.xml")))
        }

        assertEquals("Base path not set", error.message)
    }
}
