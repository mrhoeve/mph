package nl.hicts.mph.intellij.services

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal data class GitRecoveryRecord(
    val repository: String,
    val originalBranch: String,
    val backupRef: String,
    val stashMarker: String,
    val stashId: String?,
    val stashRef: String,
) {
    fun instructions(): String = "Repository: $repository\nOriginal branch: $originalBranch\nOriginal commits: $backupRef\n" +
        "Stash marker: $stashMarker\nStash object: ${stashId ?: "Inspect git stash list for the marker if interrupted during stash creation."}\n" +
        "Stash recovery ref (created once the stash is identified): $stashRef\n" +
        "The commands below recover the original work separately; leave the current checkout and any manual resolutions intact.\n" +
        "A failed stash apply may have partially restored files; do not blindly apply it again.\n" +
        "Recover in a new worktree (commands stop safely if its branch or directory already exists):\n" +
        commands(powerShell = true) + "\n" + commands(powerShell = false) + "\n" +
        "Keep the stash and both recovery refs until the synchronized work has been reviewed.\n"

    internal fun commands(powerShell: Boolean): String {
        fun quote(value: String) = "'" + value.replace("'", if (powerShell) "''" else "'\"'\"'") + "'"
        val id = backupRef.substringAfter("refs/mph/recovery/").substringBefore('/')
        val destination = Path.of(repository).toAbsolutePath().normalize().resolveSibling("mph-recovered-$id").toString()
        val source = "git -C ${quote(repository)}"
        return (if (powerShell) "PowerShell:\n" else "POSIX shell:\n") +
            "$source status\n$source log ${quote(backupRef)}\n" +
            "$source worktree add -b ${quote("mph-recovered-$id")} ${quote(destination)} ${quote(backupRef)}" +
            if (stashId == null) {
                "\n# No stash object has been recorded. Inspect the marker with:\n$source stash list\n"
            } else {
                // A failed worktree creation must never apply onto an existing checkout.
                val apply = "git -C ${quote(destination)} stash apply --index ${quote(stashId)}"
                if (powerShell) "\nif (\$LASTEXITCODE -eq 0) { $apply }\n" else " &&\n$apply\n"
            }
    }
}

/** Never truncate the last useful instructions when a replacement write fails. */
internal fun writeGitRecoveryRecord(path: Path, text: String) {
    // An interrupted temporary write must not make the run's directory unrecognizable to the catalog.
    val temporary = Files.createTempFile(path.parent.parent, "recovery-", ".tmp")
    try {
        Files.writeString(temporary, text)
        Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}
