package nl.hicts.mph.services

import nl.hicts.mph.models.Settings
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

@Service
class SettingsService {
    private val settingsDirectory: Path = Paths.get(System.getProperty("user.home"), ".mph")
    private val settingsFile: Path = settingsDirectory.resolve("settings.properties")

    fun loadSettings(): Settings {
        if (!settingsFile.exists()) {
            return Settings(null, 3)
        }

        val properties = Properties()

        Files.newInputStream(settingsFile).use { inputStream ->
            properties.load(inputStream)
        }

        val basePathStr = properties.getProperty("basePath")
            ?.takeIf { it.isNotBlank() }
        
        val maxScanDepth = properties.getProperty("maxScanDepth")?.toIntOrNull() ?: 3

        val path = basePathStr?.let { Paths.get(it) }?.takeIf { it.exists() && it.isDirectory() }

        return Settings(path, maxScanDepth)
    }

    fun saveSettings(path: Path, maxScanDepth: Int) {
        Files.createDirectories(settingsDirectory)

        val properties = Properties()
        properties.setProperty("basePath", path.toAbsolutePath().normalize().absolutePathString())
        properties.setProperty("maxScanDepth", maxScanDepth.toString())

        Files.newOutputStream(settingsFile).use { outputStream ->
            properties.store(outputStream, "MPH settings")
        }
    }
}
