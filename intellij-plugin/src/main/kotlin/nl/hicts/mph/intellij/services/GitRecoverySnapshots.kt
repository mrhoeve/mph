package nl.hicts.mph.intellij.services

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Properties
import java.util.UUID

/** Durable, byte-for-byte copies of every POM that workspace-wide alignment may edit. */
object GitRecoverySnapshots {
    fun capture(gitDirectory: Path, pomPaths: List<Path>): Path {
        require(Files.isDirectory(gitDirectory)) { "The Git recovery directory is unavailable." }
        val directory = Files.createDirectories(gitDirectory.resolve("mph-recovery").resolve("alignment-${UUID.randomUUID()}"))
        val manifest = Properties()
        pomPaths.map { it.toAbsolutePath().normalize() }.distinct().forEachIndexed { index, path ->
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "POM is missing or is a symbolic link: $path" }
            val name = "$index.pom"
            Files.copy(path, directory.resolve(name))
            manifest.setProperty(name, path.toString())
        }
        val manifestPath = directory.resolve("original-paths.properties")
        Files.newOutputStream(manifestPath).use { manifest.store(it, "Pre-alignment POM copies. Review before restoring; paths may contain later edits.") }
        return manifestPath
    }
}
