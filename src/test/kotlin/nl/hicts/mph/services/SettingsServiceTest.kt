package nl.hicts.mph.services

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SettingsServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val originalUserHome = System.getProperty("user.home")

    @AfterEach
    fun restoreUserHome() {
        System.setProperty("user.home", originalUserHome)
    }

    @Test
    fun `should return defaults when settings file does not exist`() {
        System.setProperty("user.home", tempDir.toString())

        val settings = SettingsService().loadSettings()

        assertEquals(null, settings.basePath)
        assertEquals(3, settings.maxScanDepth)
        assertEquals(null, settings.nexusIqUrl)
        assertEquals(null, settings.nexusIqUser)
        assertEquals(null, settings.nexusIqPass)
        assertEquals(null, settings.nexusIqAppIdPrefix)
        assertEquals(null, settings.nexusIqAppIdSuffix)
    }

    @Test
    fun `should round trip all configured values exactly`() {
        System.setProperty("user.home", tempDir.toString())
        val basePath = Files.createDirectories(tempDir.resolve("projects"))
        val service = SettingsService()

        service.saveSettings(
            path = basePath,
            maxScanDepth = 7,
            nexusIqUrl = "https://iq.example.org",
            nexusIqUser = "Test User",
            nexusIqPass = "test-secret",
            nexusIqAppIdPrefix = "team-",
            nexusIqAppIdSuffix = "-service"
        )

        val settings = service.loadSettings()
        assertEquals(basePath.toAbsolutePath().normalize(), settings.basePath)
        assertEquals(7, settings.maxScanDepth)
        assertEquals("https://iq.example.org", settings.nexusIqUrl)
        assertEquals("Test User", settings.nexusIqUser)
        assertEquals("test-secret", settings.nexusIqPass)
        assertEquals("team-", settings.nexusIqAppIdPrefix)
        assertEquals("-service", settings.nexusIqAppIdSuffix)
    }

    @Test
    fun `should ignore missing base directory and malformed depth`() {
        System.setProperty("user.home", tempDir.toString())
        val settingsDirectory = Files.createDirectories(tempDir.resolve(".mph"))
        Files.writeString(settingsDirectory.resolve("settings.properties"), """
            basePath=${tempDir.resolve("missing")}
            maxScanDepth=not-a-number
            nexusIqUrl=https://iq.example.org
        """.trimIndent())

        val settings = SettingsService().loadSettings()

        assertEquals(null, settings.basePath)
        assertEquals(3, settings.maxScanDepth)
        assertEquals("https://iq.example.org", settings.nexusIqUrl)
    }

    @Test
    fun `should omit optional null values when saving`() {
        System.setProperty("user.home", tempDir.toString())
        val basePath = Files.createDirectories(tempDir.resolve("projects"))

        SettingsService().saveSettings(basePath, 2)

        val content = Files.readString(tempDir.resolve(".mph/settings.properties"))
        assertEquals(false, content.contains("nexusIqUrl"))
        assertEquals(false, content.contains("nexusIqUser"))
        assertEquals(false, content.contains("nexusIqPass"))
        assertEquals(true, content.contains("maxScanDepth=2"))
    }

    @Test
    fun `should preserve existing secret when UI does not send it back`() {
        System.setProperty("user.home", tempDir.toString())
        val basePath = Files.createDirectories(tempDir.resolve("projects"))
        val service = SettingsService()
        service.saveSettings(basePath, 3, nexusIqPass = "test-secret")

        service.saveSettings(basePath, 4, nexusIqPass = null)

        assertEquals("test-secret", service.loadSettings().nexusIqPass)
        assertEquals(4, service.loadSettings().maxScanDepth)
    }
}
