package nl.hicts.mph.intellij.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vfs.LocalFileSystem
import nl.hicts.mph.intellij.model.MavenProjectInfo
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val LS_FILES = "ls-files"
private const val REV_PARSE = "rev-parse"
private const val PORCELAIN = "--porcelain"

enum class GitRebaseStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    CONFLICT,
    SKIPPED,
    FAILED,
    CANCELLED,
}

data class GitRepositoryPlan(
    val rootPath: String,
    val artifactId: String,
)

data class GitRebasePlan(
    val prefix: String,
    val repositories: List<GitRepositoryPlan>,
    val alignmentProjects: List<MavenProjectInfo>,
)

data class GitRepositoryResult(
    val repository: GitRepositoryPlan,
    val status: GitRebaseStatus,
    val message: String,
    val recoveryHint: String? = null,
    val stashPreserved: Boolean = false,
)

fun interface GitRebaseListener {
    fun onEvent(repository: GitRepositoryPlan, status: GitRebaseStatus, message: String)
}

@Service(Service.Level.PROJECT)
class GitRebaseService {
    private val workflow = GitRebaseWorkflow()
    fun createPlan(selectedProjects: List<MavenProjectInfo>, workspaceProjects: List<MavenProjectInfo>) =
        workflow.createPlan(selectedProjects, workspaceProjects)
    fun rebase(plan: GitRebasePlan, indicator: ProgressIndicator, listener: GitRebaseListener): List<GitRepositoryResult> =
        workflow.rebase(plan, indicator, listener)
    fun rebase(plan: GitRebasePlan, indicator: ProgressIndicator, listener: GitRebaseListener, owner: WorkspaceOperationCoordinator.Lease?): List<GitRepositoryResult> =
        workflow.rebase(plan, indicator, listener, owner)
}

internal class GitRebaseWorkflow(
    private val runner: GitCommandRunner = NativeGitCommandRunner(),
    private val recoveryWriter: (Path, String) -> Unit = { path, text -> Files.writeString(path, text); Unit },
) {

    fun createPlan(
        selectedProjects: List<MavenProjectInfo>,
        workspaceProjects: List<MavenProjectInfo>,
    ): GitRebasePlan {
        require(selectedProjects.isNotEmpty()) { "Select at least one Maven project." }
        val roots = selectedProjects.map { project ->
            project.gitRootPath?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("No Git repository was found for ${project.artifactId}.")
        }.distinct()
        val alignmentProjects = workspaceProjects.filter { it.gitRootPath in roots }
        val prefixes = alignmentProjects.map { project ->
            val version = currentVersion(project)
                ?: throw IllegalArgumentException("${project.artifactId} has an inherited or unresolved version.")
            GitVersionPrefix.detect(version)
                ?: throw IllegalArgumentException(
                    "${project.artifactId} has version '$version' without a recognizable prefix.",
                )
        }.distinct()
        require(prefixes.size == 1) {
            "Selected repositories do not use the same version prefix: ${prefixes.sorted().joinToString()}"
        }
        val repositories = roots.map { root ->
            val representative = alignmentProjects
                .filter { it.gitRootPath == root }
                .minByOrNull { Path.of(it.pomPath).nameCount }
                ?: throw IllegalArgumentException("No Maven project was found in $root.")
            GitRepositoryPlan(root, representative.artifactId)
        }
        return GitRebasePlan(prefixes.single(), repositories, alignmentProjects)
    }

    fun rebase(plan: GitRebasePlan, indicator: ProgressIndicator, listener: GitRebaseListener): List<GitRepositoryResult> =
        rebase(plan, indicator, listener, null)

    fun rebase(
        plan: GitRebasePlan,
        indicator: ProgressIndicator,
        listener: GitRebaseListener,
        owner: WorkspaceOperationCoordinator.Lease?,
    ): List<GitRepositoryResult> = WorkspaceOperationCoordinator.run("Synchronization", owner) {
        rebaseOwned(plan, indicator, listener)
    }

    private fun rebaseOwned(plan: GitRebasePlan, indicator: ProgressIndicator, listener: GitRebaseListener): List<GitRepositoryResult> {
        check(running.compareAndSet(false, true)) { "A synchronization is already running." }
        return try {
            plan.repositories.map { repository ->
                if (indicator.isCanceled) {
                    GitRepositoryResult(repository, GitRebaseStatus.CANCELLED, "Cancelled before processing started.")
                } else {
                    val result = rebaseRepository(repository, indicator, listener)
                    if (indicator.isCanceled && result.status == GitRebaseStatus.FAILED) {
                        result.copy(status = GitRebaseStatus.CANCELLED)
                    } else {
                        result
                    }
                }
            }.also {
                if (ApplicationManager.getApplication() != null) {
                    plan.repositories.forEach { repository ->
                        LocalFileSystem.getInstance().refreshAndFindFileByPath(repository.rootPath)?.refresh(false, true)
                    }
                }
            }
        } finally {
            running.set(false)
        }
    }

    private fun rebaseRepository(
        repository: GitRepositoryPlan,
        indicator: ProgressIndicator,
        listener: GitRebaseListener,
    ): GitRepositoryResult {
        val context = RepositoryCommandContext(repository, indicator, listener)
        return try {
            val preflight = preflight(context)
            preflight.failure?.let { return it }
            val branchName = requireNotNull(preflight.branchName)
            context.cancelled()?.let { return it }
            context.createRecovery(branchName)
            context.cancelled()?.let { return context.withRecovery(it) }
            val stash = stashWorkingTree(context)
            context.stashId = stash.stashId ?: context.stashId
            context.recordRecovery()
            stash.failure?.let { return context.withRecovery(it) }
            context.cancelled()?.let { return context.withRecovery(it) }
            performRebase(context, branchName, stash.stashId)?.let { return context.withRecovery(it) }
            context.cancelled()?.let { return context.withRecovery(it) }
            restoreStash(context, stash.stashId)?.let { return context.withRecovery(it) }
            context.cancelled()?.let { return context.withRecovery(it) }
            context.withRecovery(GitRepositoryResult(
                repository,
                GitRebaseStatus.SUCCESS,
                "Rebased $branchName onto origin/develop and restored uncommitted work. Recovery backups retained.",
            ))
        } catch (error: Exception) {
            context.withRecovery(failed(repository, "Synchronization stopped.", error.message ?: error.javaClass.simpleName))
        }
    }

    private fun preflight(context: RepositoryCommandContext): PreflightResult {
        context.emit("Starting Git preflight")
        if (!Files.isDirectory(context.root.resolve(".git"))) {
            return PreflightResult(failure = skipped(context.repository, "No .git directory was found."))
        }
        if (gitOperationInProgress { arguments -> context.command(*arguments) }) {
            return PreflightResult(failure = skipped(context.repository, "Another Git operation is already in progress."))
        }
        val branch = context.command("symbolic-ref", QUIET, "--short", "HEAD")
        if (branch.exitCode != 0 || branch.output.isBlank()) {
            return PreflightResult(failure = skipped(context.repository, "The repository has a detached HEAD."))
        }
        val branchName = branch.output.trim()
        if (branchName in PROTECTED_BRANCHES) {
            return PreflightResult(failure = skipped(context.repository, "The current branch '$branchName' is protected from this operation."))
        }
        context.emit("Fetching origin/develop")
        val hidden = context.command(LS_FILES, "-v", "-z")
        if (hidden.exitCode != 0 || hidden.output.split('\u0000').any {
                it.isNotEmpty() && (it[0] == 'S' || it[0].isLowerCase())
            }) {
            return PreflightResult(failure = skipped(context.repository, "Sparse checkout or hidden index changes require manual synchronization."))
        }
        if (Files.exists(context.root.resolve(".gitmodules")) ||
            context.requireCommand(LS_FILES, "--stage", "-z").split('\u0000').any { it.startsWith("160000 ") }) {
            return PreflightResult(failure = skipped(context.repository, "Repositories with submodules require manual synchronization."))
        }
        val fetch = context.command("fetch", "--no-tags", "origin", "+refs/heads/develop:$REMOTE_DEVELOP", stream = true)
        if (fetch.exitCode != 0) {
            return PreflightResult(failure = failed(context.repository, "Fetching origin/develop failed.", fetch.diagnostic))
        }
        val remoteDevelop = context.command("show-ref", VERIFY, QUIET, REMOTE_DEVELOP)
        if (remoteDevelop.exitCode != 0) {
            return PreflightResult(failure = skipped(context.repository, "Remote branch origin/develop was not found."))
        }
        context.upstream = context.requireCommand(REV_PARSE, "--verify", "$REMOTE_DEVELOP^{commit}").trim()
        checkIgnoredFileCollisions(context)?.let { return PreflightResult(failure = it) }
        context.cancelled()?.let { return PreflightResult(failure = it) }
        updateLocalDevelop(context)?.let { return PreflightResult(failure = it) }
        return PreflightResult(branchName)
    }

    private fun checkIgnoredFileCollisions(context: RepositoryCommandContext): GitRepositoryResult? {
        val ignored = context.requireCommand(LS_FILES, "--others", "--ignored", "--exclude-standard", "-z")
            .split('\u0000').filter(String::isNotEmpty)
        if (ignored.isNotEmpty()) {
            // Include intermediate replayed trees: an ignored file may have been tracked and later deleted.
            val commits = listOf(context.upstream) + context.requireCommand("rev-list", "${context.upstream}..HEAD")
                .lineSequence().filter(String::isNotBlank).toList()
            val ignoredPaths = ignored.map { context.root.resolve(it).normalize() }.toSet()
            val ignoredAncestors = ignoredPaths.flatMap { path ->
                generateSequence(path.parent) { it.parent }.takeWhile { it.startsWith(context.root) }.toList()
            }.toSet()
            for (commit in commits) {
                context.cancelled()?.let { return it }
                val incoming = context.requireCommand("ls-tree", "-r", "--name-only", "-z", commit)
                    .split('\u0000').filter(String::isNotEmpty).map { context.root.resolve(it).normalize() }
                if (incoming.any { remote ->
                        remote in ignoredAncestors || generateSequence(remote) { it.parent }
                            .takeWhile { it.startsWith(context.root) }.any { it in ignoredPaths }
                    }) {
                    return skipped(context.repository, "Ignored local files overlap files used by the rebase. Move or back them up first.")
                }
            }
        }
        return null
    }

    private fun updateLocalDevelop(context: RepositoryCommandContext): GitRepositoryResult? {
        val worktrees = context.requireCommand("worktree", "list", PORCELAIN)
        if (worktrees.lineSequence().any { it == "branch $LOCAL_DEVELOP" }) {
            return skipped(context.repository, "Local develop is checked out in another worktree.")
        }
        val refs = context.requireCommand("for-each-ref", "--format=%(refname) %(objectname)", LOCAL_DEVELOP)
            .lineSequence().firstOrNull { it.startsWith("$LOCAL_DEVELOP ") }?.substringAfter(' ')?.trim().orEmpty()
        val oldId = refs.ifEmpty { "0".repeat(context.upstream.length) }
        if (refs.isNotEmpty()) {
            val fastForward = context.command("merge-base", "--is-ancestor", oldId, context.upstream)
            if (fastForward.exitCode != 0) {
                return skipped(context.repository, "Local develop has commits that are not on origin/develop.")
            }
        }
        val updateDevelop = context.command("update-ref", LOCAL_DEVELOP, context.upstream, oldId)
        return updateDevelop.takeIf { it.exitCode != 0 }?.let {
            failed(context.repository, "Local develop could not be updated.", it.diagnostic)
        }
    }

    private fun stashWorkingTree(context: RepositoryCommandContext): StashResult {
        val status = context.command("status", PORCELAIN, "--untracked-files=all")
        if (status.exitCode != 0) {
            return StashResult(
                failure = failed(context.repository, "The working tree could not be inspected.", status.output),
            )
        }
        if (status.output.isBlank()) return StashResult()

        context.emit("Stashing tracked and untracked work")
        val marker = context.stashMarker
        val stash = context.command(
            "stash", "push", "--include-untracked", "--message",
            marker, stream = true,
        )
        val stashId = findStashByMarker(context, marker)
        context.stashId = stashId
        context.recordRecovery()
        if (stash.exitCode != 0) {
            return StashResult(
                failure = failed(
                    context.repository,
                    "Uncommitted work could not be stashed.",
                    stash.diagnostic,
                    stashPreserved = stashId != null,
                ),
            )
        }
        if (stashId == null) {
            return StashResult(
                failure = failed(
                    context.repository,
                    "Git did not create an identifiable safety stash. The rebase was not started.",
                    stash.diagnostic,
                    stashPreserved = true,
                ),
            )
        }

        val remainingStatus = context.command("status", PORCELAIN, "--untracked-files=all")
        if (remainingStatus.exitCode != 0 || remainingStatus.output.isNotBlank()) {
            return StashResult(
                failure = failed(
                    context.repository,
                    "Not all working-tree changes could be stored safely. The rebase was not started.",
                    remainingStatus.output,
                    stashPreserved = true,
                ),
            )
        }
        return StashResult(stashId)
    }

    private fun findStashByMarker(context: RepositoryCommandContext, marker: String): String? =
        context.command("stash", "list", "--format=%H%x00%gs").output
            .lineSequence()
            .map { line -> line.substringBefore('\u0000') to line.substringAfter('\u0000', "") }
            .firstOrNull { (_, subject) -> marker in subject }
            ?.first
            ?.takeIf(String::isNotBlank)

    private fun performRebase(
        context: RepositoryCommandContext,
        branchName: String,
        stashId: String?,
    ): GitRepositoryResult? {
        context.emit("Rebasing $branchName onto origin/develop")
        var rebase = context.command(
            "-c", "commit.gpgSign=false", "-c", "rerere.enabled=false", "rebase", "--no-autostash", "--no-update-refs", "--no-fork-point",
            "--no-autosquash", "--rebase-merges", "--reapply-cherry-picks", "--empty=keep", "--keep-empty",
            context.upstream, stream = true,
        )
        var attempts = 0
        while (rebase.exitCode != 0 && attempts++ < MAX_AUTOMATIC_CONTINUES) {
            context.cancelled()?.let { return it }
            val conflicts = conflictedFiles(context.root)
            if (conflicts.isEmpty()) {
                return failed(context.repository, "Rebase did not complete.", rebase.diagnostic, stashId != null)
            }
            if (!resolveVersionOnlyConflicts(context.root, conflicts)) {
                return GitRepositoryResult(
                    context.repository,
                    GitRebaseStatus.CONFLICT,
                    "Rebase stopped because source or non-version conflicts require manual resolution.",
                    "Resolve conflicts in IntelliJ and continue the rebase. The MPH stash is preserved when one was created.",
                    stashPreserved = stashId != null,
                )
            }
            val add = runGit(context.root, listOf("add", "--") + conflicts)
            if (add.exitCode != 0) return failed(context.repository, "Resolved POM files could not be staged.", add.diagnostic, stashId != null)
            context.cancelled()?.let { return it }
            context.emit("Continuing after resolving version-only POM conflicts")
            rebase = runGit(
                context.root,
                listOf("-c", "commit.gpgSign=false", "-c", "rerere.enabled=false", "rebase", "--continue"),
                context::emit,
                mapOf("GIT_EDITOR" to "true", "GIT_SEQUENCE_EDITOR" to "true"),
            )
        }
        return rebase.takeIf { it.exitCode != 0 }?.let {
            failed(context.repository, "Rebase did not complete.", it.diagnostic, stashId != null)
        }
    }

    private fun restoreStash(context: RepositoryCommandContext, stashId: String?): GitRepositoryResult? {
        if (stashId == null) return null
        context.emit("Restoring uncommitted work")
        val apply = context.command("stash", "apply", "--index", stashId, stream = true)
        if (apply.exitCode != 0) {
            return GitRepositoryResult(
                context.repository,
                GitRebaseStatus.CONFLICT,
                "The branch was rebased, but restoring uncommitted work did not complete. ${apply.diagnostic.trim()}",
                "Inspect the working tree before applying anything again; restoration may be partial. The original index and work remain in the MPH stash.",
                stashPreserved = true,
            )
        }
        // Retain the original index/worktree snapshot through alignment and later user review.
        return null
    }

    private fun currentVersion(project: MavenProjectInfo): String? {
        val application = ApplicationManager.getApplication()
        val documentContent = if (
            application != null && (application.isDispatchThread || application.isReadAccessAllowed)
        ) {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(project.pomPath)
            virtualFile?.let { file -> FileDocumentManager.getInstance().getDocument(file)?.text }
        } else {
            null
        }
        val content = documentContent ?: runCatching { Files.readString(Path.of(project.pomPath)) }.getOrNull()
        return content?.let(PomReferenceVersionEditor::findProjectVersion) ?: project.version
    }

    private fun gitOperationInProgress(command: (Array<out String>) -> GitCommandResult): Boolean {
        val paths = listOf("rebase-merge", "rebase-apply", "sequencer", "MERGE_HEAD", "REBASE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "BISECT_LOG", "index.lock")
        return paths.any { name ->
            val result = command(arrayOf(REV_PARSE, "--path-format=absolute", "--git-path", name))
            result.exitCode != 0 || Files.exists(Path.of(result.output.trim()))
        }
    }

    private fun conflictedFiles(root: Path): List<String> {
        val result = runGit(root, listOf("diff", "--name-only", "-z", "--diff-filter=U"))
        check(result.exitCode == 0) { "Could not inspect conflicts: ${result.diagnostic}" }
        return result.output.split('\u0000').filter(String::isNotEmpty)
    }

    private fun resolveVersionOnlyConflicts(root: Path, conflicts: List<String>): Boolean {
        // Validate every file before changing any, so a mixed conflict set stays untouched.
        val resolved = conflicts.map { relative ->
            val path = root.resolve(relative).normalize()
            if (!path.startsWith(root) || path.fileName.toString() != "pom.xml" || Files.isSymbolicLink(path)) return false
            val content = GitVersionConflictResolver.resolvedContent(Files.readString(path)) ?: return false
            path to content
        }
        resolved.forEach { (path, content) -> Files.writeString(path, content) }
        return true
    }

    private fun runGit(
        root: Path,
        arguments: List<String>,
        progress: ((String) -> Unit)? = null,
        environment: Map<String, String> = emptyMap(),
    ): GitCommandResult = runner.execute(root, arguments, progress, environment)

    private fun skipped(repository: GitRepositoryPlan, reason: String) = GitRepositoryResult(
        repository,
        GitRebaseStatus.SKIPPED,
        "$reason Skipped.",
        "Resolve the repository state manually, then retry.",
    )

    private fun failed(
        repository: GitRepositoryPlan,
        message: String,
        details: String,
        stashPreserved: Boolean = false,
    ) = GitRepositoryResult(
        repository,
        GitRebaseStatus.FAILED,
        listOf(message, details.trim()).filter(String::isNotBlank).joinToString(" "),
        if (stashPreserved) {
            "Inspect the Git state. The MPH stash was preserved."
        } else {
            "Inspect the repository and retry after it is in a clean, safe Git state."
        },
        stashPreserved,
    )

    private data class PreflightResult(
        val branchName: String? = null,
        val failure: GitRepositoryResult? = null,
    )

    private data class StashResult(
        val stashId: String? = null,
        val failure: GitRepositoryResult? = null,
    )

    private inner class RepositoryCommandContext(
        val repository: GitRepositoryPlan,
        val indicator: ProgressIndicator,
        private val listener: GitRebaseListener,
    ) {
        val root: Path = Path.of(repository.rootPath).toAbsolutePath().normalize()

        var upstream = ""
        var stashId: String? = null
        private val recoveryId = UUID.randomUUID().toString()
        val stashMarker = "mph: rebase ${root.fileName} on develop ($recoveryId)"
        private var recoveryFile: Path? = null
        private var originalBranch = ""
        private var backupRef = ""

        fun createRecovery(branch: String) {
            originalBranch = branch
            val gitDirectory = Path.of(requireCommand(REV_PARSE, "--absolute-git-dir").trim())
            recoveryFile = Files.createDirectories(gitDirectory.resolve("mph-recovery").resolve(recoveryId)).resolve("recovery.txt")
            backupRef = "refs/mph/recovery/$recoveryId/head"
            requireCommand("update-ref", backupRef, "HEAD", "0".repeat(upstream.length))
            recordRecovery()
        }

        fun recordRecovery() {
            recoveryFile?.let { file ->
                recoveryWriter(file, GitRecoveryRecord(root.toString(), originalBranch, backupRef, stashMarker, stashId).instructions())
            }
        }

        fun withRecovery(result: GitRepositoryResult): GitRepositoryResult = result.copy(
            recoveryHint = listOfNotNull(result.recoveryHint, recoveryFile?.let { "Recovery instructions: $it" }).joinToString(" ").ifBlank { null },
            stashPreserved = result.stashPreserved || stashId != null,
        )

        fun cancelled(): GitRepositoryResult? = if (indicator.isCanceled) {
            GitRepositoryResult(repository, GitRebaseStatus.CANCELLED, "Stopped between Git commands. Version alignment was skipped.")
        } else null

        fun requireCommand(vararg arguments: String): String {
            val result = command(*arguments)
            check(result.exitCode == 0) { "Git ${arguments.first()} failed: ${result.diagnostic}" }
            return result.output
        }

        fun emit(message: String) = listener.onEvent(repository, GitRebaseStatus.RUNNING, message)

        fun command(vararg arguments: String, stream: Boolean = false): GitCommandResult =
            runGit(root, arguments.toList(), if (stream) ::emit else null)
    }

    private companion object {
        val running = AtomicBoolean(false)
        const val MAX_AUTOMATIC_CONTINUES = 100
        const val REMOTE_DEVELOP = "refs/remotes/origin/develop"
        const val LOCAL_DEVELOP = "refs/heads/develop"
        const val QUIET = "--quiet"
        const val VERIFY = "--verify"
        val PROTECTED_BRANCHES = setOf("main", "master", "develop")
    }
}

object GitVersionPrefix {
    private val prefixedVersion = Regex("""^(.*?)(\d+(?:\.\d+)+(?:[-.][A-Za-z0-9]+)*)$""")

    fun detect(version: String): String? = prefixedVersion.matchEntire(version)
        ?.groupValues?.get(1)?.takeIf(String::isNotBlank)
}

object GitVersionConflictResolver {
    private val versionElement = Regex(
        """\s*<((?:[\w.-]*version)|revision|changelist|sha1)>[^<>]*</\1>\s*""",
        RegexOption.IGNORE_CASE,
    )

    fun resolve(path: Path): Boolean {
        val resolved = resolvedContent(Files.readString(path)) ?: return false
        Files.writeString(path, resolved)
        return true
    }

    internal fun resolvedContent(content: String): String? {
        val separator = if ("\r\n" in content) "\r\n" else "\n"
        return resolvedConflictLines(content.split(separator))?.joinToString(separator)
    }

    private fun resolvedConflictLines(lines: List<String>): List<String>? {
        val resolved = buildList {
            var index = 0
            var foundConflict = false
            while (index < lines.size) {
                if (!lines[index].startsWith("<<<<<<<")) {
                    add(lines[index++])
                    continue
                }
                foundConflict = true
                val conflict = readConflict(lines, index + 1) ?: return null
                if (!isVersionOnly(conflict.current) || !isVersionOnly(conflict.incoming)) return null
                if (versionTags(conflict.current) != versionTags(conflict.incoming)) return null
                addAll(conflict.current)
                index = conflict.nextIndex
            }
            if (!foundConflict) return null
        }
        return resolved
    }

    private fun readConflict(lines: List<String>, start: Int): ConflictBlock? {
        val separator = (start until lines.size).firstOrNull { lines[it].startsWith("=======") } ?: return null
        val end = (separator + 1 until lines.size).firstOrNull { lines[it].startsWith(">>>>>>>") } ?: return null
        return ConflictBlock(lines.subList(start, separator), lines.subList(separator + 1, end), end + 1)
    }

    private fun versionTags(lines: List<String>): List<String> = lines.filter(String::isNotBlank)
        .map { requireNotNull(versionElement.matchEntire(it)).groupValues[1] }

    private fun isVersionOnly(lines: List<String>): Boolean {
        val meaningful = lines.filter(String::isNotBlank)
        return meaningful.isNotEmpty() && meaningful.all(versionElement::matches)
    }

    private data class ConflictBlock(
        val current: List<String>,
        val incoming: List<String>,
        val nextIndex: Int,
    )
}
