package nl.hicts.mph.intellij.services

internal data class GitRecoveryRecord(
    val repository: String,
    val originalBranch: String,
    val backupRef: String,
    val stashMarker: String,
    val stashId: String?,
) {
    fun instructions(): String = "Repository: $repository\nOriginal branch: $originalBranch\nOriginal commits: $backupRef\n" +
        "Stash marker: $stashMarker\nStash object: ${stashId ?: "Inspect git stash list for the marker if interrupted during stash creation."}\n" +
        "Inspect Git status first. Finish or abort an active rebase before restoring work.\n" +
        "A failed stash apply may have partially restored files; do not blindly apply it again.\n" +
        "Inspect original commits with: git log $backupRef\n" +
        "Recover on a separate clean worktree with: git worktree add -b recovery-branch <new-directory> $backupRef\n" +
        "Then, in that worktree, restore the index and files with: git stash apply --index <stash-object>\n" +
        "Keep the stash and backup ref until the synchronized work has been reviewed.\n"
}
