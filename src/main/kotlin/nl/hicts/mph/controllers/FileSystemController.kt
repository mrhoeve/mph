package nl.hicts.mph.controllers

import com.fasterxml.jackson.annotation.JsonIgnore
import nl.hicts.mph.models.Settings
import nl.hicts.mph.services.SettingsService
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
class FileSystemController(
    private val settingsService: SettingsService
) {

    @GetMapping("/api/filesystem/current")
    fun current(): FolderResponse {
        val settings = settingsService.loadSettings()
        val rememberedBasePath = settings.basePath

        return if (rememberedBasePath != null) {
            folderResponse(rememberedBasePath, remembered = true, maxScanDepth = settings.maxScanDepth)
        } else {
            folderResponse(Paths.get(System.getProperty("user.dir")), remembered = false, maxScanDepth = settings.maxScanDepth)
        }
    }

    @GetMapping("/api/filesystem/folders")
    fun folders(@RequestParam path: String): FolderResponse {
        val settings = settingsService.loadSettings()
        return folderResponse(Paths.get(path), remembered = false, maxScanDepth = settings.maxScanDepth)
    }

    @PostMapping("/api/filesystem/base")
    fun saveBase(@RequestBody request: SaveSettingsRequest): FolderResponse {
        val path = Paths.get(request.path)

        if (!path.exists() || !path.isDirectory()) {
            throw InvalidFolderException("Folder does not exist or is not a directory: ${request.path}")
        }

        settingsService.saveSettings(path, request.maxScanDepth, request.nexusIqUrl, request.nexusIqUser, request.nexusIqPass, request.nexusIqAppIdPrefix, request.nexusIqAppIdSuffix)

        return folderResponse(path, remembered = true, maxScanDepth = request.maxScanDepth)
    }

    private fun folderResponse(path: Path, remembered: Boolean, maxScanDepth: Int): FolderResponse {
        val normalizedPath = path.toAbsolutePath().normalize()
        val settings = settingsService.loadSettings()

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
            nexusIqUrl = settings.nexusIqUrl,
            nexusIqUser = settings.nexusIqUser,
            nexusIqPass = settings.nexusIqPass,
            nexusIqAppIdPrefix = settings.nexusIqAppIdPrefix,
            nexusIqAppIdSuffix = settings.nexusIqAppIdSuffix,
            children = children
        )
    }
}

data class FolderResponse(
    val path: String,
    val parentPath: String?,
    val remembered: Boolean,
    val maxScanDepth: Int,
    val nexusIqUrl: String? = null,
    val nexusIqUser: String? = null,
    @get:JsonIgnore
    val nexusIqPass: String? = null,
    val nexusIqAppIdPrefix: String? = null,
    val nexusIqAppIdSuffix: String? = null,
    val children: List<FolderItem>
)

data class FolderItem(
    val name: String,
    val path: String
)

data class SaveSettingsRequest(
    val path: String,
    val maxScanDepth: Int,
    val nexusIqUrl: String? = null,
    val nexusIqUser: String? = null,
    val nexusIqPass: String? = null,
    val nexusIqAppIdPrefix: String? = null,
    val nexusIqAppIdSuffix: String? = null
)

@ResponseStatus(HttpStatus.BAD_REQUEST)
class InvalidFolderException(message: String) : RuntimeException(message)
