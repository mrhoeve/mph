package nl.hicts.mph.intellij.services

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import nl.hicts.mph.intellij.model.GitWorkspaceStatus
import nl.hicts.mph.intellij.model.MavenProjectInfo
import java.nio.charset.StandardCharsets
import java.nio.file.Path

data class GitBranchResult(val rootPath: String, val success: Boolean, val message: String)
data class LatestTagVersion(val tagName: String, val version: String)

@Service(Service.Level.PROJECT)
class GitWorkspaceService {
    fun status(rootPath: String): GitWorkspaceStatus? {
        val branch = run(rootPath, "rev-parse", "--abbrev-ref", "HEAD").takeIf(CommandResult::success)
            ?.output?.trim()?.takeIf { it.isNotBlank() && it != "HEAD" } ?: return null
        val counts = run(rootPath, "rev-list", "--left-right", "--count", "HEAD...refs/remotes/origin/develop")
        val (ahead, behind) = GitOutputParser.aheadBehind(counts.output).takeIf { counts.success } ?: (0 to 0)
        return GitWorkspaceStatus(branch, ahead, behind)
    }

    fun createOrCheckoutBranch(rootPaths: Collection<String>, branchName: String): List<GitBranchResult> {
        require(branchName.isNotBlank()) { "Enter a Git branch name." }
        require(GitOutputParser.validBranchName(branchName)) { "'$branchName' is not a safe Git branch name." }
        return rootPaths.distinct().map { root -> createOrCheckoutBranch(root, branchName) }
    }

    fun latestVersion(project: MavenProjectInfo): LatestTagVersion? {
        val root = project.gitRootPath ?: return null
        run(root, "fetch", "origin", "--tags", "--prune") // Local tags remain usable when the network is unavailable.
        val tags = run(root, "for-each-ref", "--sort=-creatordate", "--format=%(refname:short)", "refs/tags")
        if (!tags.success) return null
        val relativePom = Path.of(root).toAbsolutePath().normalize()
            .relativize(Path.of(project.pomPath).toAbsolutePath().normalize())
            .joinToString("/")
        return tags.output.lineSequence().map(String::trim).filter(String::isNotBlank).firstNotNullOfOrNull { tag ->
            val pom = run(root, "show", "$tag:$relativePom")
            val version = pom.takeIf(CommandResult::success)?.output?.let(PomReferenceVersionEditor::findProjectVersion)
            version?.takeIf(String::isNotBlank)?.let { LatestTagVersion(tag, it) }
        }
    }

    private fun createOrCheckoutBranch(root: String, branchName: String): GitBranchResult {
        val fetch = run(root, "fetch", "origin", "--prune")
        if (!fetch.success) return GitBranchResult(root, false, "Could not fetch origin: ${fetch.error}")
        if (isClean(root) && run(root, "rev-parse", "--abbrev-ref", "@{upstream}").success) {
            val pull = run(root, "pull", "--ff-only")
            if (!pull.success) return GitBranchResult(root, false, "Could not fast-forward the current branch: ${pull.error}")
        }
        val local = run(root, "show-ref", "--verify", "--quiet", "refs/heads/$branchName").success
        val remote = run(root, "show-ref", "--verify", "--quiet", "refs/remotes/origin/$branchName").success
        val checkout = when {
            local -> run(root, "checkout", branchName)
            remote -> run(root, "checkout", "--track", "-b", branchName, "origin/$branchName")
            else -> run(root, "checkout", "-b", branchName)
        }
        return if (checkout.success) GitBranchResult(root, true, "Checked out $branchName")
        else GitBranchResult(root, false, checkout.error.ifBlank { "Git checkout failed." })
    }

    private fun isClean(root: String): Boolean = run(root, "status", "--porcelain").let { it.success && it.output.isBlank() }

    private fun run(root: String, vararg arguments: String): CommandResult {
        val command = GeneralCommandLine("git")
            .withParameters(arguments.toList())
            .withWorkDirectory(root)
            .withCharset(StandardCharsets.UTF_8)
        return try {
            val output = CapturingProcessHandler(command).runProcess(COMMAND_TIMEOUT_MS)
            CommandResult(output.exitCode == 0, output.stdout, output.stderr.trim())
        } catch (error: Exception) {
            CommandResult(false, "", error.message ?: error.javaClass.simpleName)
        }
    }

    private data class CommandResult(val success: Boolean, val output: String, val error: String)

    private companion object {
        const val COMMAND_TIMEOUT_MS = 120_000
    }
}

object GitOutputParser {
    fun aheadBehind(output: String): Pair<Int, Int>? {
        val values = output.trim().split(Regex("\\s+")).mapNotNull(String::toIntOrNull)
        return values.takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }

    fun validBranchName(value: String): Boolean = value.isNotBlank() &&
        !value.startsWith('-') && !value.endsWith('/') && !value.endsWith('.') &&
        ".." !in value && "@{" !in value && value.none { it.isWhitespace() || it in "~^:?*[\\" }
}
