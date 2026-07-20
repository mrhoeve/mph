package nl.hicts.mph.intellij.services

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import nl.hicts.mph.intellij.model.MavenProjectInfo
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    private val activeHandlers = ConcurrentHashMap.newKeySet<OSProcessHandler>()
    private val cancelRequested = AtomicBoolean(false)

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

    fun rebase(
        plan: GitRebasePlan,
        indicator: ProgressIndicator,
        listener: GitRebaseListener,
    ): List<GitRepositoryResult> {
        cancelRequested.set(false)
        return try {
            plan.repositories.map { repository ->
                if (indicator.isCanceled || cancelRequested.get()) {
                    GitRepositoryResult(repository, GitRebaseStatus.CANCELLED, "Cancelled before processing started.")
                } else {
                    val result = rebaseRepository(repository, indicator, listener)
                    if ((indicator.isCanceled || cancelRequested.get()) && result.status == GitRebaseStatus.FAILED) {
                        GitRepositoryResult(repository, GitRebaseStatus.CANCELLED, "Cancelled while processing the repository.")
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
            cancelRequested.set(false)
        }
    }

    fun cancel() {
        cancelRequested.set(true)
        activeHandlers.forEach(OSProcessHandler::destroyProcess)
    }

    private fun rebaseRepository(
        repository: GitRepositoryPlan,
        indicator: ProgressIndicator,
        listener: GitRebaseListener,
    ): GitRepositoryResult {
        val context = RepositoryCommandContext(repository, indicator, listener)
        val preflight = preflight(context)
        preflight.failure?.let { return it }
        val branchName = requireNotNull(preflight.branchName)
        val stash = stashWorkingTree(context)
        stash.failure?.let { return it }
        performRebase(context, branchName, stash.stashId)?.let { return it }
        restoreStash(context, stash.stashId)?.let { return it }

        listener.onEvent(repository, GitRebaseStatus.SUCCESS, "Rebase completed and uncommitted work was restored")
        return GitRepositoryResult(
            repository,
            GitRebaseStatus.SUCCESS,
            "Rebased $branchName onto origin/develop and restored uncommitted work.",
        )
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
        val fetch = context.command("fetch", "origin", "develop", stream = true)
        if (fetch.exitCode != 0) {
            return PreflightResult(failure = failed(context.repository, "Fetching origin/develop failed.", fetch.output))
        }
        val remoteDevelop = context.command("show-ref", VERIFY, QUIET, REMOTE_DEVELOP)
        if (remoteDevelop.exitCode != 0) {
            return PreflightResult(failure = skipped(context.repository, "Remote branch origin/develop was not found."))
        }
        updateLocalDevelop(context)?.let { return PreflightResult(failure = it) }
        return PreflightResult(branchName)
    }

    private fun updateLocalDevelop(context: RepositoryCommandContext): GitRepositoryResult? {
        val localDevelop = context.command("show-ref", VERIFY, QUIET, LOCAL_DEVELOP)
        if (localDevelop.exitCode == 0) {
            val fastForward = context.command("merge-base", "--is-ancestor", LOCAL_DEVELOP, REMOTE_DEVELOP)
            if (fastForward.exitCode != 0) {
                return skipped(context.repository, "Local develop has commits that are not on origin/develop.")
            }
        }
        val updateDevelop = context.command("update-ref", LOCAL_DEVELOP, REMOTE_DEVELOP)
        return updateDevelop.takeIf { it.exitCode != 0 }?.let {
            failed(context.repository, "Local develop could not be updated.", it.output)
        }
    }

    private fun stashWorkingTree(context: RepositoryCommandContext): StashResult {
        if (context.command("status", "--porcelain").output.isBlank()) return StashResult()
        context.emit("Stashing tracked and untracked work")
        val stash = context.command(
            "stash", "push", "--include-untracked", "--message",
            "mph: rebase ${context.root.fileName} on develop", stream = true,
        )
        if (stash.exitCode != 0) {
            return StashResult(failure = failed(context.repository, "Uncommitted work could not be stashed.", stash.output))
        }
        return StashResult(context.command("rev-parse", "refs/stash").output.trim().takeIf(String::isNotBlank))
    }

    private fun performRebase(
        context: RepositoryCommandContext,
        branchName: String,
        stashId: String?,
    ): GitRepositoryResult? {
        context.emit("Rebasing $branchName onto origin/develop")
        var rebase = context.command("-c", "commit.gpgSign=false", "rebase", "origin/develop", stream = true)
        var attempts = 0
        while (rebase.exitCode != 0 && attempts++ < MAX_AUTOMATIC_CONTINUES) {
            val conflicts = conflictedFiles(context.root, context.indicator)
            if (conflicts.isEmpty() || !resolveVersionOnlyConflicts(context.root, conflicts)) {
                return GitRepositoryResult(
                    context.repository,
                    GitRebaseStatus.CONFLICT,
                    "Rebase stopped because source or non-version conflicts require manual resolution.",
                    "Resolve conflicts in IntelliJ and continue the rebase. The MPH stash is preserved when one was created.",
                    stashPreserved = stashId != null,
                )
            }
            val add = runGit(context.root, listOf("add", "--") + conflicts, context.indicator)
            if (add.exitCode != 0) return failed(context.repository, "Resolved POM files could not be staged.", add.output, stashId != null)
            context.emit("Continuing after resolving version-only POM conflicts")
            rebase = runGit(
                context.root,
                listOf("-c", "commit.gpgSign=false", "rebase", "--continue"),
                context.indicator,
                context::emit,
                mapOf("GIT_EDITOR" to "true", "GIT_SEQUENCE_EDITOR" to "true"),
            )
        }
        return rebase.takeIf { it.exitCode != 0 }?.let {
            failed(context.repository, "Rebase did not complete.", it.output, stashId != null)
        }
    }

    private fun restoreStash(context: RepositoryCommandContext, stashId: String?): GitRepositoryResult? {
        if (stashId == null) return null
        context.emit("Restoring uncommitted work")
        val apply = context.command("stash", "apply", stashId, stream = true)
        if (apply.exitCode != 0) {
            val conflicts = conflictedFiles(context.root, context.indicator)
            if (conflicts.isEmpty() || !resolveVersionOnlyConflicts(context.root, conflicts)) {
                return GitRepositoryResult(
                    context.repository,
                    GitRebaseStatus.CONFLICT,
                    "The branch was rebased, but restoring uncommitted work caused conflicts.",
                    "Resolve the working-tree conflicts manually. The MPH stash was preserved.",
                    stashPreserved = true,
                )
            }
            val add = runGit(context.root, listOf("add", "--") + conflicts, context.indicator)
            if (add.exitCode != 0) {
                return failed(context.repository, "Resolved stash conflicts could not be staged.", add.output, true)
            }
        }
        context.command("reset")
        dropStash(context.root, stashId, context.indicator)
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

    private fun gitOperationInProgress(command: (Array<out String>) -> GitCommandResult): Boolean =
        listOf("REBASE_HEAD", "MERGE_HEAD", "CHERRY_PICK_HEAD").any { ref ->
            command(arrayOf("rev-parse", QUIET, VERIFY, ref)).exitCode == 0
        }

    private fun conflictedFiles(root: Path, indicator: ProgressIndicator): List<String> =
        runGit(root, listOf("diff", "--name-only", "--diff-filter=U"), indicator).output
            .lineSequence().map(String::trim).filter(String::isNotBlank).toList()

    private fun resolveVersionOnlyConflicts(root: Path, conflicts: List<String>): Boolean = conflicts.all { relative ->
        val path = root.resolve(relative).normalize()
        path.startsWith(root) && path.fileName.toString() == "pom.xml" && GitVersionConflictResolver.resolve(path)
    }

    private fun dropStash(root: Path, stashId: String, indicator: ProgressIndicator) {
        val stashes = runGit(root, listOf("stash", "list", "--format=%H"), indicator).output.lines()
        val index = stashes.indexOfFirst { it.trim() == stashId }
        if (index >= 0) runGit(root, listOf("stash", "drop", "stash@{$index}"), indicator)
    }

    private fun runGit(
        root: Path,
        arguments: List<String>,
        indicator: ProgressIndicator,
        progress: ((String) -> Unit)? = null,
        environment: Map<String, String> = emptyMap(),
    ): GitCommandResult {
        val output = StringBuilder()
        val command = GeneralCommandLine("git")
            .withParameters(arguments)
            .withWorkDirectory(root.toFile())
            .withEnvironment(environment)
        command.charset = StandardCharsets.UTF_8
        val handler = try {
            OSProcessHandler(command)
        } catch (error: Exception) {
            return GitCommandResult(-1, error.message ?: error.javaClass.simpleName)
        }
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType != ProcessOutputTypes.STDOUT && outputType != ProcessOutputTypes.STDERR) return
                synchronized(output) { output.append(event.text) }
                progress?.invoke(event.text.trimEnd())
            }
        })
        activeHandlers += handler
        return try {
            handler.startNotify()
            while (!handler.waitFor(200)) {
                if (indicator.isCanceled || cancelRequested.get()) handler.destroyProcess()
            }
            GitCommandResult(handler.exitCode ?: -1, synchronized(output) { output.toString() })
        } finally {
            activeHandlers -= handler
        }
    }

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

    private data class GitCommandResult(val exitCode: Int, val output: String)

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

        fun emit(message: String) = listener.onEvent(repository, GitRebaseStatus.RUNNING, message)

        fun command(vararg arguments: String, stream: Boolean = false): GitCommandResult =
            runGit(root, arguments.toList(), indicator, if (stream) ::emit else null)
    }

    private companion object {
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
        """\s*<((?:[\w.-]*version)|revision|changelist|sha1)>.*</\1>\s*""",
        RegexOption.IGNORE_CASE,
    )

    fun resolve(path: Path): Boolean {
        val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
        val resolved = resolvedConflictLines(lines) ?: return false
        Files.write(path, resolved, StandardCharsets.UTF_8)
        return true
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
