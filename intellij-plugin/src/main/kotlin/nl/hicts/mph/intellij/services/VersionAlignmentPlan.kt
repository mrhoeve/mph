package nl.hicts.mph.intellij.services

import com.intellij.openapi.application.PathManager
import nl.hicts.mph.intellij.model.MavenProjectInfo
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID

data class AlignmentEdit(val project: MavenProjectInfo, val before: String, val after: String)
internal data class AlignmentInput(val project: MavenProjectInfo, val content: String, val diskBytes: ByteArray)
class VersionAlignmentPlan internal constructor(
    internal val inputs: List<AlignmentInput>,
    val edits: List<AlignmentEdit>,
    val result: BulkVersionUpdateResult,
)
class AlignmentApplyException(message: String, cause: Throwable) : IllegalStateException(message, cause)

/** Stored outside IDE caches, including unsaved editor text as well as exact disk bytes. */
object AlignmentRecoveryStore {
    fun root(): Path = Path.of(PathManager.getConfigPath(), "mph-recovery")

    internal fun capture(inputs: List<AlignmentInput>): Path {
        val directory = Files.createDirectories(root().resolve("alignment-${UUID.randomUUID()}"))
        val manifest = Properties()
        inputs.forEachIndexed { index, input ->
            Files.write(directory.resolve("$index.pom"), input.diskBytes)
            Files.writeString(directory.resolve("$index.editor.txt"), input.content)
            manifest.setProperty("$index.pom", Path.of(input.project.pomPath).toAbsolutePath().normalize().toString())
        }
        Files.newOutputStream(directory.resolve("original-paths.properties")).use {
            manifest.store(it, "Review before restoring. .pom contains original disk bytes; .editor.txt contains original editor text.")
        }
        return directory
    }
}
