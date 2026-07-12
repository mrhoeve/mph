package nl.hicts.mph.controllers

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import nl.hicts.mph.models.Settings
import nl.hicts.mph.services.SettingsService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileSystemControllerTest {

    @TempDir
    lateinit var tempDir: Path

    private val settingsService = mockk<SettingsService>()
    private val controller = FileSystemController(settingsService)

    @Test
    fun `current should return remembered folder settings and sorted child folders`() {
        Files.createDirectories(tempDir.resolve("zeta"))
        Files.createDirectories(tempDir.resolve("Alpha"))
        Files.writeString(tempDir.resolve("file.txt"), "ignored")
        val settings = configuredSettings(tempDir)
        every { settingsService.loadSettings() } returns settings

        val response = controller.current()

        assertEquals(tempDir.toAbsolutePath().normalize().toString(), response.path)
        assertEquals(true, response.remembered)
        assertEquals(5, response.maxScanDepth)
        assertEquals("https://iq.example.org", response.nexusIqUrl)
        assertEquals("test-user", response.nexusIqUser)
        assertEquals("test-secret", response.nexusIqPass)
        assertEquals("prefix-", response.nexusIqAppIdPrefix)
        assertEquals("-suffix", response.nexusIqAppIdSuffix)
        assertEquals(listOf("Alpha", "zeta"), response.children.map { it.name })
        assertEquals(
            listOf(tempDir.resolve("Alpha"), tempDir.resolve("zeta")).map { it.toAbsolutePath().normalize().toString() },
            response.children.map { it.path }
        )
    }

    @Test
    fun `folders should browse requested path without marking it remembered`() {
        every { settingsService.loadSettings() } returns configuredSettings(tempDir)
        controller.current()

        val response = controller.folders(tempDir.toString())

        assertEquals(false, response.remembered)
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), response.path)
        assertEquals(tempDir.parent?.toAbsolutePath()?.normalize()?.toString(), response.parentPath)
    }

    @Test
    fun `saveBase should persist every setting and return selected folder`() {
        every { settingsService.loadSettings() } returns configuredSettings(tempDir)
        every { settingsService.saveSettings(any(), any(), any(), any(), any(), any(), any()) } returns Unit
        controller.current()
        val request = SaveSettingsRequest(
            path = tempDir.toString(),
            maxScanDepth = 8,
            nexusIqUrl = "https://new-iq.example.org",
            nexusIqUser = "api-user",
            nexusIqPass = "api-secret",
            nexusIqAppIdPrefix = "new-",
            nexusIqAppIdSuffix = "-app"
        )

        val response = controller.saveBase(request)

        verify(exactly = 1) {
            settingsService.saveSettings(
                tempDir,
                8,
                "https://new-iq.example.org",
                "api-user",
                "api-secret",
                "new-",
                "-app"
            )
        }
        assertEquals(true, response.remembered)
        assertEquals(8, response.maxScanDepth)
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), response.path)
    }

    @Test
    fun `saveBase should reject missing folder without saving`() {
        val missing = tempDir.resolve("missing")

        val exception = assertThrows(InvalidFolderException::class.java) {
            controller.saveBase(SaveSettingsRequest(missing.toString(), 3))
        }

        assertEquals("Folder was not provided by the server: $missing", exception.message)
        verify(exactly = 0) { settingsService.saveSettings(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `folders should reject a regular file`() {
        val file = tempDir.resolve("pom.xml")
        Files.writeString(file, "<project/>")
        every { settingsService.loadSettings() } returns configuredSettings(tempDir)
        controller.current()

        val exception = assertThrows(InvalidFolderException::class.java) {
            controller.folders(file.toString())
        }

        assertEquals(
            "Folder was not provided by the server: $file",
            exception.message
        )
    }

    @Test
    fun `folders should reject an existing directory that was not exposed`() {
        val exposed = Files.createDirectories(tempDir.resolve("exposed"))
        val unexposed = Files.createDirectories(tempDir.resolve("unexposed").resolve("nested"))
        every { settingsService.loadSettings() } returns configuredSettings(exposed)
        controller.current()

        val exception = assertThrows(InvalidFolderException::class.java) {
            controller.folders(unexposed.toString())
        }

        assertEquals("Folder was not provided by the server: $unexposed", exception.message)
    }

    private fun configuredSettings(path: Path) = Settings(
        basePath = path,
        maxScanDepth = 5,
        nexusIqUrl = "https://iq.example.org",
        nexusIqUser = "test-user",
        nexusIqPass = "test-secret",
        nexusIqAppIdPrefix = "prefix-",
        nexusIqAppIdSuffix = "-suffix"
    )
}
