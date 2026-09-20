package nl.hicts.mph.intellij.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.merge.GitConflictResolver
import git4idea.repo.GitRepositoryManager
import com.intellij.dvcs.repo.Repository
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class IdeaConflictResolutionService(private val project: Project) {
    fun resolve(rootPath: String): String = WorkspaceOperationCoordinator.run("Conflict resolution") {
        var message = "Conflict resolution was closed. Recovery copies and stashes are retained."
        ProgressManager.getInstance().run(object : Task.Modal(project, "Resolve Git conflicts", false) {
            override fun run(indicator: ProgressIndicator) {
                val root = LocalFileSystem.getInstance().refreshAndFindFileByPath(rootPath)
                    ?: error("Repository is unavailable: $rootPath")
                val repository = GitRepositoryManager.getInstance(project).getRepositoryForRoot(root)
                    ?: error("Register this Git repository in IntelliJ before resolving conflicts.")
                repository.update()
                val initial = NativeGitCommandRunner().execute(Path.of(rootPath), listOf("diff", "--name-only", "-z", "--diff-filter=U"), null, emptyMap())
                check(initial.exitCode == 0) { initial.diagnostic }
                if (initial.output.isBlank()) {
                    message = "No unmerged files are available in the merge editor. Inspect the recovery instructions; stash restoration failures such as untracked-file collisions may require manual recovery."
                    return
                }
                val rebasing = repository.state == Repository.State.REBASING
                val params = GitConflictResolver.Params(project).setReverse(rebasing)
                    .setMergeDescription("Resolve conflicts in $rootPath. MPH retains your recovery copies and stash.")
                // This opens IntelliJ's native merge UI, including its binary/deleted-file handling.
                // It deliberately does not continue Git or reapply a potentially partially restored stash.
                GitConflictResolver(project, listOf(root), params).mergeNoProceed()
                repository.update()
                val remaining = NativeGitCommandRunner().execute(Path.of(rootPath), listOf("diff", "--name-only", "-z", "--diff-filter=U"), null, emptyMap())
                check(remaining.exitCode == 0) { remaining.diagnostic }
                message = if (remaining.output.isNotBlank()) "Unresolved conflicts remain. Recovery copies and stashes are retained."
                else if (rebasing) "Conflicts resolved. Close this dialog and continue the rebase in IntelliJ. Then inspect the retained stash instructions before restoring local work and realigning versions."
                else "Conflicts resolved. Review restored local work, then run Realign Versions. The recovery stash is retained."
            }
        })
        message
    }
}
