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
        val root = Path.of(repository.rootPath).toAbsolutePath().normalize()
        fun emit(message: String) = listener.onEvent(repository, GitRebaseStatus.RUNNING, message)
        fun command(vararg arguments: String, stream: Boolean = false) =
            runGit(root, arguments.toList(), indicator, if (stream) ::emit else null)

        listener.onEvent(repository, GitRebaseStatus.RUNNING, "Starting Git preflight")
        if (!Files.isDirectory(root.resolve(".git"))) {
            return skipped(repository, "No .git directory was found.")
        }
        if (gitOperationInProgress { arguments -> command(*arguments) }) {
            return skipped(repository, "Another Git operation is already in progress.")
        }
        val branch = command("symbolic-ref", "--quiet", "--short", "HEAD")
        if (branch.exitCode != 0 || branch.output.isBlank()) {
            return skipped(repository, "The repository has a detached HEAD.")
        }
        val branchName = branch.output.trim()
        if (branchName in PROTECTED_BRANCHES) {
            return skipped(repository, "The current branch '$branchName' is protected from this operation.")
        }

        emit("Fetching origin/develop")
        val fetch = command("fetch", "origin", "develop", stream = true)
        if (fetch.exitCode != 0) return failed(repository, "Fetching origin/develop failed.", fetch.output)
        val remoteDevelop = command("show-ref", "--verify", "--quiet", REMOTE_DEVELOP)
        if (remoteDevelop.exitCode != 0) return skipped(repository, "Remote branch origin/develop was not found.")

        val localDevelop = command("show-ref", "--verify", "--quiet", LOCAL_DEVELOP)
        if (localDevelop.exitCode == 0) {
            val fastForward = command("merge-base", "--is-ancestor", LOCAL_DEVELOP, REMOTE_DEVELOP)
            if (fastForward.exitCode != 0) {
                return skipped(repository, "Local develop has commits that are not on origin/develop.")
            }
        }
        val updateDevelop = command("update-ref", LOCAL_DEVELOP, REMOTE_DEVELOP)
        if (updateDevelop.exitCode != 0) return failed(repository, "Local develop could not be updated.", updateDevelop.output)

        val dirty = command("status", "--porcelain").output.isNotBlank()
        val stashId = if (dirty) {
            emit("Stashing tracked and untracked work")
            val stash = command(
                "stash",
                "push",
                "--include-untracked",
                "--message",
                "mph: rebase ${root.fileName} on develop",
                stream = true,
            )
            if (stash.exitCode != 0) return failed(repository, "Uncommitted work could not be stashed.", stash.output)
            command("rev-parse", "refs/stash").output.trim().takeIf(String::isNotBlank)
        } else {
            null
        }

        emit("Rebasing $branchName onto origin/develop")
        var rebase = command("-c", "commit.gpgSign=false", "rebase", "origin/develop", stream = true)
        var attempts = 0
        while (rebase.exitCode != 0 && attempts++ < MAX_AUTOMATIC_CONTINUES) {
            val conflicts = conflictedFiles(root, indicator)
            if (conflicts.isEmpty() || !resolveVersionOnlyConflicts(root, conflicts)) {
                return GitRepositoryResult(
                    repository,
                    GitRebaseStatus.CONFLICT,
                    "Rebase stopped because source or non-version conflicts require manual resolution.",
                    "Resolve conflicts in IntelliJ and continue the rebase. The MPH stash is preserved when one was created.",
                    stashPreserved = stashId != null,
                )
            }
            val add = runGit(root, listOf("add", "--") + conflicts, indicator)
            if (add.exitCode != 0) return failed(repository, "Resolved POM files could not be staged.", add.output, stashId != null)
            emit("Continuing after resolving version-only POM conflicts")
            rebase = runGit(
                root,
                listOf("-c", "commit.gpgSign=false", "rebase", "--continue"),
                indicator,
                ::emit,
                mapOf("GIT_EDITOR" to "true", "GIT_SEQUENCE_EDITOR" to "true"),
            )
        }
        if (rebase.exitCode != 0) {
            return failed(repository, "Rebase did not complete.", rebase.output, stashId != null)
        }

        if (stashId != null) {
            emit("Restoring uncommitted work")
            val apply = command("stash", "apply", stashId, stream = true)
            if (apply.exitCode != 0) {
                val conflicts = conflictedFiles(root, indicator)
                if (conflicts.isEmpty() || !resolveVersionOnlyConflicts(root, conflicts)) {
                    return GitRepositoryResult(
                        repository,
                        GitRebaseStatus.CONFLICT,
                        "The branch was rebased, but restoring uncommitted work caused conflicts.",
                        "Resolve the working-tree conflicts manually. The MPH stash was preserved.",
                        stashPreserved = true,
                    )
                }
                val add = runGit(root, listOf("add", "--") + conflicts, indicator)
                if (add.exitCode != 0) return failed(repository, "Resolved stash conflicts could not be staged.", add.output, true)
            }
            command("reset")
            dropStash(root, stashId, indicator)
        }

        listener.onEvent(repository, GitRebaseStatus.SUCCESS, "Rebase completed and uncommitted work was restored")
        return GitRepositoryResult(
            repository,
            GitRebaseStatus.SUCCESS,
            "Rebased $branchName onto origin/develop and restored uncommitted work.",
        )
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
            command(arrayOf("rev-parse", "--quiet", "--verify", ref)).exitCode == 0
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

    private companion object {
        const val MAX_AUTOMATIC_CONTINUES = 100
        const val REMOTE_DEVELOP = "refs/remotes/origin/develop"
        const val LOCAL_DEVELOP = "refs/heads/develop"
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
        """\s*<((?:[A-Za-z0-9_.-]*version)|revision|changelist|sha1)>.*</\1>\s*""",
        RegexOption.IGNORE_CASE,
    )

    fun resolve(path: Path): Boolean {
        val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
        val resolved = mutableListOf<String>()
        var index = 0
        var foundConflict = false
        while (index < lines.size) {
            if (!lines[index].startsWith("<<<<<<<")) {
                resolved += lines[index++]
                continue
            }
            foundConflict = true
            index++
            val current = mutableListOf<String>()
            while (index < lines.size && !lines[index].startsWith("=======")) current += lines[index++]
            if (index >= lines.size) return false
            index++
            val incoming = mutableListOf<String>()
            while (index < lines.size && !lines[index].startsWith(">>>>>>>")) incoming += lines[index++]
            if (index >= lines.size || !isVersionOnly(current) || !isVersionOnly(incoming)) return false
            index++
            resolved += current
        }
        if (foundConflict) Files.write(path, resolved, StandardCharsets.UTF_8)
        return foundConflict
    }

    private fun isVersionOnly(lines: List<String>): Boolean {
        val meaningful = lines.filter(String::isNotBlank)
        return meaningful.isNotEmpty() && meaningful.all(versionElement::matches)
    }
}
