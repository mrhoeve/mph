package nl.hicts.mph.controllers

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

@RestController
class FileSystemController {

    private val settingsDirectory: Path = Paths.get(System.getProperty("user.home"), ".mph")
    private val settingsFile: Path = settingsDirectory.resolve("settings.properties")

    @GetMapping("/api/filesystem/current")
    fun current(): FolderResponse {
        val settings = loadSettings()
        val rememberedBasePath = settings.basePath

        return if (rememberedBasePath != null) {
            folderResponse(rememberedBasePath, remembered = true, maxScanDepth = settings.maxScanDepth)
        } else {
            folderResponse(Paths.get(System.getProperty("user.dir")), remembered = false, maxScanDepth = settings.maxScanDepth)
        }
    }

    @GetMapping("/api/filesystem/folders")
    fun folders(@RequestParam path: String): FolderResponse {
        val settings = loadSettings()
        return folderResponse(Paths.get(path), remembered = false, maxScanDepth = settings.maxScanDepth)
    }

    @PostMapping("/api/filesystem/base")
    fun saveBase(@RequestBody request: SaveSettingsRequest): FolderResponse {
        val path = Paths.get(request.path)

        if (!path.exists() || !path.isDirectory()) {
            throw InvalidFolderException("Folder does not exist or is not a directory: ${request.path}")
        }

        saveSettings(path, request.maxScanDepth)

        return folderResponse(path, remembered = true, maxScanDepth = request.maxScanDepth)
    }

    private fun folderResponse(path: Path, remembered: Boolean, maxScanDepth: Int): FolderResponse {
        val normalizedPath = path.toAbsolutePath().normalize()

        if (!normalizedPath.exists() || !normalizedPath.isDirectory()) {
            throw InvalidFolderException("Folder does not exist or is not a directory: $normalizedPath")
        }

        val children = try {
            Files.list(normalizedPath).use { stream ->
                stream
                    .filter { Files.isDirectory(it) }
                    .sorted(Comparator.comparing<Path, String> { it.fileName.toString().lowercase() })
                    .map {
                        FolderItem(
                            name = it.fileName.toString(),
                            path = it.toAbsolutePath().normalize().absolutePathString()
                        )
                    }
                    .toList()
            }
        } catch (exception: IOException) {
            throw InvalidFolderException("Folder cannot be read: $normalizedPath")
        } catch (exception: SecurityException) {
            throw InvalidFolderException("Folder access is not allowed: $normalizedPath")
        }

        return FolderResponse(
            path = normalizedPath.absolutePathString(),
            parentPath = normalizedPath.parent?.absolutePathString(),
            remembered = remembered,
            maxScanDepth = maxScanDepth,
            children = children
        )
    }

    private fun loadSettings(): Settings {
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

    private fun saveSettings(path: Path, maxScanDepth: Int) {
        Files.createDirectories(settingsDirectory)

        val properties = Properties()
        properties.setProperty("basePath", path.toAbsolutePath().normalize().absolutePathString())
        properties.setProperty("maxScanDepth", maxScanDepth.toString())

        Files.newOutputStream(settingsFile).use { outputStream ->
            properties.store(outputStream, "MPH settings")
        }
    }
}

data class Settings(
    val basePath: Path?,
    val maxScanDepth: Int
)

data class FolderResponse(
    val path: String,
    val parentPath: String?,
    val remembered: Boolean,
    val maxScanDepth: Int,
    val children: List<FolderItem>
)

data class FolderItem(
    val name: String,
    val path: String
)

data class SaveSettingsRequest(
    val path: String,
    val maxScanDepth: Int
)

@ResponseStatus(HttpStatus.BAD_REQUEST)
class InvalidFolderException(message: String) : RuntimeException(message)
