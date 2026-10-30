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
        "Inspect Git status first. Finish or abort an active rebase before restoring work.\n" +
        "A failed stash apply may have partially restored files; do not blindly apply it again.\n" +
        "Inspect original commits with: git log $backupRef\n" +
        "Recover on a separate clean worktree with: git worktree add -b recovery-branch <new-directory> $backupRef\n" +
        "Then, in that worktree, restore the index and files with: git stash apply --index ${stashId ?: "<stash-object>"}\n" +
        "Keep the stash and both recovery refs until the synchronized work has been reviewed.\n"
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
