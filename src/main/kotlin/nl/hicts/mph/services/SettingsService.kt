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
        val nexusIqUrl = properties.getProperty("nexusIqUrl")
        val nexusIqUser = properties.getProperty("nexusIqUser")
        val nexusIqPass = properties.getProperty("nexusIqPass")
        val nexusIqAppIdPrefix = properties.getProperty("nexusIqAppIdPrefix")
        val nexusIqAppIdSuffix = properties.getProperty("nexusIqAppIdSuffix")

        val path = basePathStr?.let { Paths.get(it) }?.takeIf { it.exists() && it.isDirectory() }

        return Settings(path, maxScanDepth, nexusIqUrl, nexusIqUser, nexusIqPass, nexusIqAppIdPrefix, nexusIqAppIdSuffix)
    }

    fun saveSettings(path: Path, maxScanDepth: Int, nexusIqUrl: String? = null, nexusIqUser: String? = null, nexusIqPass: String? = null, nexusIqAppIdPrefix: String? = null, nexusIqAppIdSuffix: String? = null) {
        Files.createDirectories(settingsDirectory)

        val properties = Properties()
        properties.setProperty("basePath", path.toAbsolutePath().normalize().absolutePathString())
        properties.setProperty("maxScanDepth", maxScanDepth.toString())
        nexusIqUrl?.let { properties.setProperty("nexusIqUrl", it) }
        nexusIqUser?.let { properties.setProperty("nexusIqUser", it) }
        nexusIqPass?.let { properties.setProperty("nexusIqPass", it) }
        nexusIqAppIdPrefix?.let { properties.setProperty("nexusIqAppIdPrefix", it) }
        nexusIqAppIdSuffix?.let { properties.setProperty("nexusIqAppIdSuffix", it) }

        Files.newOutputStream(settingsFile).use { outputStream ->
            properties.store(outputStream, "MPH settings")
        }
    }
}
