package nl.hicts.mph.intellij.services

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

internal data class RecoveryRun(val directory: Path, val files: Map<String, String>, val details: String) {
    override fun toString(): String = "${directory.fileName} — ${directory.parent}"
}

/** Catalogs only MPH-owned, flat recovery directories. Never follows symlinks or restores work implicitly. */
internal class RecoveryCatalog(roots: List<Path>) {
    private val roots = roots.map { it.toAbsolutePath().normalize() }.distinct()
    fun list(): List<RecoveryRun> = roots.flatMap { root ->
        if (!Files.isDirectory(root, NOFOLLOW_LINKS) || root.toRealPath() != root) emptyList()
        else Files.list(root).use { paths -> paths.map { inspect(it) }.filter { it != null }.toList().filterNotNull() }
    }.sortedByDescending { Files.getLastModifiedTime(it.directory).toMillis() }

    private fun inspect(directory: Path): RecoveryRun? {
        val path = directory.toAbsolutePath().normalize()
        if (path.parent !in roots || !NAME.matches(path.fileName.toString()) || !Files.isDirectory(path, NOFOLLOW_LINKS) || path.toRealPath() != path) return null
        val files: List<Path> = Files.list(path).use { it.toList() }
        if (files.isEmpty() || files.any { !Files.isRegularFile(it, NOFOLLOW_LINKS) || !FILE.matches(it.fileName.toString()) }) return null
        val hashes = files.sorted().associate { it.fileName.toString() to sha(Files.readAllBytes(it)) }
        val details = buildString {
            append("Recovery directory: $path\n\n")
            files.filter { it.fileName.toString() in setOf("recovery.txt", "original-paths.properties") }.forEach {
                append(Files.readString(it)).append("\n")
            }
            append("\nRetained files:\n").append(hashes.keys.joinToString("\n"))
            append("\n\nDeleting this run removes these copies/instructions only. Git recovery refs and stashes are retained. Review the synchronized work before cleanup.")
        }
        return RecoveryRun(path, hashes, details)
    }

    fun deleteReviewed(run: RecoveryRun) = WorkspaceOperationCoordinator.run("Recovery cleanup") {
        val current = inspect(run.directory)
        check(current != null && current.files == run.files) { "Recovery files changed after review. Refresh the list and review again." }
        // Flat deletion cannot traverse into a working tree or a linked directory.
        current.files.keys.forEach { name -> Files.delete(current.directory.resolve(name)) }
        Files.delete(current.directory)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private companion object {
        val NAME = Regex("(?:alignment-)?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val FILE = Regex("(?:recovery\\.txt|original-paths\\.properties|[0-9]+\\.pom|[0-9]+\\.editor\\.txt)")
    }
}
