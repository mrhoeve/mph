package nl.hicts.mph.services

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.StashApplyFailureException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.TagOpt
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.NetRCCredentialsProvider
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.treewalk.TreeWalk
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

enum class DevelopRebaseStatus {
    SUCCESS, CONFLICT, SKIPPED, FAILED
}

data class DevelopRebaseResult(
    val repositoryPath: String,
    val branchName: String?,
    val status: DevelopRebaseStatus,
    val message: String,
    val recoveryHint: String? = null,
    val stashPreserved: Boolean = false
)

@Service
class GitService {
    private val logger = LoggerFactory.getLogger(GitService::class.java)

    init {
        try {
            // Configure JGit to use modern SSH implementation and system settings
            // SshdSessionFactory picks up settings from ~/.ssh/config and uses ssh-agent
            val sshSessionFactory = SshdSessionFactory()
            SshSessionFactory.setInstance(sshSessionFactory)

            // Set default credentials provider to include netrc
            CredentialsProvider.setDefault(NetRCCredentialsProvider())
            logger.info("JGit configured with SshdSessionFactory and NetRCCredentialsProvider")
        } catch (e: Exception) {
            logger.error("Failed to configure JGit transport settings: ${e.message}", e)
        }
    }
    
    private val noTag = Any()
    private val tagCache = ConcurrentHashMap<String, Any>() // TagInfo or noTag sentinel
    private val repoTagsCache = ConcurrentHashMap<File, List<org.eclipse.jgit.lib.Ref>>()
    private val fetchedRepos = ConcurrentHashMap.newKeySet<File>()

    fun clearCache() {
        tagCache.clear()
        repoTagsCache.clear()
        fetchedRepos.clear()
    }

    fun prepareBranch(projectPath: File, branchName: String) {
        if (branchName.isBlank()) return

        val repoDir = findGitRoot(projectPath) ?: run {
            logger.warn("No Git repository found for path: $projectPath")
            return
        }

        logger.info("Preparing branch '$branchName' for repository at ${repoDir.absolutePath}")

        Git.open(repoDir).use { git ->
            try {
                // 1. git pull origin
                try {
                    logger.info("Performing git pull origin for ${repoDir.absolutePath}")
                    // Pull requires configured upstream, or we can try to pull explicitly
                    git.pull().setRemote("origin").setCredentialsProvider(CredentialsProvider.getDefault()).call()
                } catch (e: Exception) {
                    logger.warn("Could not perform git pull origin: ${e.message}")
                    // Fallback to just fetching if pull fails
                    try {
                        git.fetch().setRemote("origin").setCredentialsProvider(CredentialsProvider.getDefault()).call()
                    } catch (fe: Exception) {
                        logger.warn("Could not fetch from origin: ${fe.message}")
                    }
                }

                // 2. Check if branch exists
                val localBranches = git.branchList().call().toList()
                val existsLocally = localBranches.any { it.name == "refs/heads/$branchName" }

                if (existsLocally) {
                    logger.info("Branch '$branchName' exists locally, checking it out.")
                    git.checkout()
                        .setName(branchName)
                        .call()
                } else {
                    val remoteBranches = git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call().toList()
                    val remoteBranchName = "refs/remotes/origin/$branchName"
                    val existsOnRemote = remoteBranches.any { it.name == remoteBranchName }

                    if (existsOnRemote) {
                        logger.info("Branch '$branchName' exists on remote, checking it out and tracking.")
                        git.checkout()
                            .setCreateBranch(true)
                            .setName(branchName)
                            .setStartPoint("origin/$branchName")
                            .call()
                    } else {
                        logger.info("Branch '$branchName' does not exist, creating it.")
                        git.checkout()
                            .setCreateBranch(true)
                            .setName(branchName)
                            .call()
                    }
                }
            } catch (e: Exception) {
                logger.error("Git operation failed for ${repoDir.absolutePath}: ${e.message}", e)
                throw RuntimeException("Git operation failed for branch '$branchName': ${e.message}")
            }
        }
    }

    fun syncDevelop(projectPath: File, mergeIntoCurrent: Boolean = false): String? {
        val repoDir = findGitRoot(projectPath) ?: run {
            logger.warn("No Git repository found for path: $projectPath")
            return null
        }

        Git.open(repoDir).use { git ->
            val repository = git.repository
            val currentBranch = repository.branch
            if (currentBranch == null) return "Could not determine current branch for ${repoDir.name}"
            logger.info("Syncing develop for ${repoDir.absolutePath}. Current branch: $currentBranch")
            try {
                return syncDevelopRepository(git, repoDir, currentBranch, mergeIntoCurrent)
            } catch (e: Exception) {
                logger.error("Sync develop failed for ${repoDir.absolutePath}: ${e.message}", e)
                restoreBranch(git, currentBranch)
                throw RuntimeException("Sync develop failed for ${repoDir.name}: ${e.message}")
            }
        }
    }

    /**
     * Rebases the current feature branch onto origin/develop without committing
     * working-tree changes. Only POM conflicts whose complete conflict hunks
     * contain version elements are resolved automatically.
     */
    fun rebaseOnDevelop(projectPath: File, progress: (String) -> Unit = {}): DevelopRebaseResult {
        val repoDir = findGitRoot(projectPath) ?: return DevelopRebaseResult(
            projectPath.absolutePath,
            null,
            DevelopRebaseStatus.SKIPPED,
            "No Git repository was found.",
            "Check that the project is located inside a Git working tree."
        )

        return try {
            Git.open(repoDir).use { git -> rebaseRepositoryOnDevelop(git, repoDir, progress) }
        } catch (e: Exception) {
            logger.error("Rebase on develop failed for ${repoDir.absolutePath}: ${e.message}", e)
            DevelopRebaseResult(
                repoDir.absolutePath,
                null,
                DevelopRebaseStatus.FAILED,
                "Rebase failed: ${e.message ?: e.javaClass.simpleName}",
                "Inspect the repository and retry after it is in a clean, safe Git state."
            )
        }
    }

    private fun rebaseRepositoryOnDevelop(
        git: Git,
        repoDir: File,
        progress: (String) -> Unit
    ): DevelopRebaseResult {
        val repository = git.repository
        val fullBranch = repository.fullBranch
        val branchName = fullBranch?.takeIf { it.startsWith(Constants.R_HEADS) }?.removePrefix(Constants.R_HEADS)
        if (repository.repositoryState != RepositoryState.SAFE) {
            return skippedRebase(repoDir, branchName, "Repository is already ${repository.repositoryState.description}.")
        }
        if (branchName == null) {
            return skippedRebase(repoDir, null, "Repository has a detached HEAD.")
        }
        if (branchName in PROTECTED_BRANCHES) {
            return skippedRebase(repoDir, branchName, "The current branch '$branchName' is protected from this operation.")
        }

        progress("Fetching origin/develop")
        git.fetch()
            .setRemote("origin")
            .setCredentialsProvider(CredentialsProvider.getDefault())
            .call()
        val remoteDevelop = repository.resolve(REMOTE_DEVELOP_REF)
            ?: return skippedRebase(repoDir, branchName, "Remote branch origin/develop was not found.")

        val localDevelopUpdate = updateLocalDevelop(repository, remoteDevelop)
        if (localDevelopUpdate != null) return skippedRebase(repoDir, branchName, localDevelopUpdate)

        val stashId = stashWorkingTree(git, repoDir, progress)
        progress("Rebasing $branchName onto origin/develop")
        val rebaseResult = try {
            runRebaseResolvingVersionConflicts(git, repoDir)
        } catch (e: Exception) {
            logger.error("Rebase failed after stashing ${repoDir.absolutePath}: ${e.message}", e)
            return DevelopRebaseResult(
                repoDir.absolutePath,
                branchName,
                DevelopRebaseStatus.FAILED,
                "Rebase failed: ${e.message ?: e.javaClass.simpleName}",
                "Inspect the Git state. The MPH stash was preserved and must only be reapplied after the rebase is resolved or aborted.",
                stashPreserved = stashId != null
            )
        }
        if (!rebaseResult.status.isSuccessful) {
            return DevelopRebaseResult(
                repoDir.absolutePath,
                branchName,
                if (rebaseResult.status == RebaseResult.Status.STOPPED ||
                    rebaseResult.status == RebaseResult.Status.CONFLICTS
                ) DevelopRebaseStatus.CONFLICT else DevelopRebaseStatus.FAILED,
                "Rebase stopped with status ${rebaseResult.status}.",
                "Resolve the conflicts, run git rebase --continue, then reapply the preserved MPH stash if present.",
                stashId != null
            )
        }

        if (stashId != null) {
            progress("Restoring uncommitted work")
            val stashResult = restoreStash(git, repoDir, stashId)
            if (stashResult != null) return stashResult.copy(branchName = branchName)
        }

        progress("Rebase completed")
        return DevelopRebaseResult(
            repoDir.absolutePath,
            branchName,
            DevelopRebaseStatus.SUCCESS,
            "Rebased $branchName onto origin/develop and restored uncommitted work."
        )
    }

    private fun skippedRebase(repoDir: File, branchName: String?, reason: String) = DevelopRebaseResult(
        repoDir.absolutePath,
        branchName,
        DevelopRebaseStatus.SKIPPED,
        "$reason Skipped.",
        "Resolve the repository state manually, then retry."
    )

    private fun updateLocalDevelop(repository: Repository, remoteDevelop: ObjectId): String? {
        val localRef = repository.findRef(LOCAL_DEVELOP_REF)
        if (localRef == null) {
            val create = repository.updateRef(LOCAL_DEVELOP_REF)
            create.setNewObjectId(remoteDevelop)
            create.setRefLogMessage("mph: initialize develop from origin/develop", false)
            val result = create.update()
            return if (result.name in setOf("NEW", "NO_CHANGE", "FAST_FORWARD")) null
            else "Local develop could not be created from origin/develop ($result)."
        }

        val localId = localRef.objectId
        if (localId == remoteDevelop) return null
        val fastForward = RevWalk(repository).use { walk ->
            walk.isMergedInto(walk.parseCommit(localId), walk.parseCommit(remoteDevelop))
        }
        if (!fastForward) return "Local develop has commits that are not on origin/develop."

        val update = repository.updateRef(LOCAL_DEVELOP_REF)
        update.setExpectedOldObjectId(localId)
        update.setNewObjectId(remoteDevelop)
        update.setRefLogMessage("mph: fast-forward develop from origin/develop", false)
        val result = update.update()
        return if (result.name in setOf("NO_CHANGE", "FAST_FORWARD")) null
        else "Local develop could not be fast-forwarded ($result)."
    }

    private fun stashWorkingTree(git: Git, repoDir: File, progress: (String) -> Unit): ObjectId? {
        if (git.status().call().isClean) return null
        progress("Stashing tracked and untracked work")
        return git.stashCreate()
            .setIncludeUntracked(true)
            .setWorkingDirectoryMessage("mph: rebase ${repoDir.name} on develop")
            .call()
    }

    private fun runRebaseResolvingVersionConflicts(git: Git, repoDir: File): RebaseResult {
        val config = git.repository.config
        val hadLocalSigningSetting = config.getNames(CONFIG_COMMIT_SECTION, null, false)
            .any { it.equals(COMMIT_GPG_SIGN, ignoreCase = true) }
        val localSigningSetting = config.getString(CONFIG_COMMIT_SECTION, null, COMMIT_GPG_SIGN)
        config.setBoolean(CONFIG_COMMIT_SECTION, null, COMMIT_GPG_SIGN, false)
        try {
            var result = git.rebase().setUpstream(REMOTE_DEVELOP_REF).call()
            var attempts = 0
            while ((result.status == RebaseResult.Status.STOPPED || result.status == RebaseResult.Status.CONFLICTS) &&
                attempts++ < MAX_AUTOMATIC_REBASE_CONTINUES
            ) {
                if (!resolveVersionOnlyPomConflicts(git, repoDir)) return result
                result = git.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call()
            }
            return result
        } finally {
            if (hadLocalSigningSetting && localSigningSetting != null) {
                config.setString(CONFIG_COMMIT_SECTION, null, COMMIT_GPG_SIGN, localSigningSetting)
            } else {
                config.unset(CONFIG_COMMIT_SECTION, null, COMMIT_GPG_SIGN)
            }
        }
    }

    private fun restoreStash(git: Git, repoDir: File, stashId: ObjectId): DevelopRebaseResult? {
        try {
            git.stashApply()
                .setStashRef(stashId.name)
                .setRestoreUntracked(true)
                .call()
        } catch (_: StashApplyFailureException) {
            if (!resolveVersionOnlyPomConflicts(git, repoDir)) {
                return DevelopRebaseResult(
                    repoDir.absolutePath,
                    null,
                    DevelopRebaseStatus.CONFLICT,
                    "The branch was rebased, but restoring uncommitted work caused conflicts.",
                    "Resolve the working-tree conflicts manually. The MPH stash was preserved.",
                    stashPreserved = true
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to restore stash for ${repoDir.absolutePath}: ${e.message}", e)
            return DevelopRebaseResult(
                repoDir.absolutePath,
                null,
                DevelopRebaseStatus.FAILED,
                "The branch was rebased, but the uncommitted work could not be restored: ${e.message}",
                "The MPH stash was preserved. Inspect it with git stash list before applying it manually.",
                stashPreserved = true
            )
        }

        // Keep every restored change uncommitted and avoid leaving auto-resolved POMs staged.
        git.reset().setMode(ResetCommand.ResetType.MIXED).call()
        dropStash(git, stashId)
        return null
    }

    private fun dropStash(git: Git, stashId: ObjectId) {
        val index = git.stashList().call().indexOfFirst { it.id == stashId }
        if (index >= 0) git.stashDrop().setStashRef(index).call()
    }

    private fun resolveVersionOnlyPomConflicts(git: Git, repoDir: File): Boolean {
        val conflicts = git.status().call().conflicting
        if (conflicts.isEmpty()) return false
        val conflictFiles = conflicts.map { relativePath ->
            val resolved = safeRepositoryPath(repoDir.toPath(), relativePath) ?: return false
            if (resolved.fileName.toString() != "pom.xml" || !resolveVersionConflictFile(resolved)) return false
            relativePath
        }
        conflictFiles.forEach { git.add().addFilepattern(it.replace(File.separatorChar, '/')).call() }
        return true
    }

    private fun safeRepositoryPath(root: Path, relativePath: String): Path? {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val resolved = normalizedRoot.resolve(relativePath).normalize()
        return resolved.takeIf { it.startsWith(normalizedRoot) }
    }

    internal fun resolveVersionConflictFile(path: Path): Boolean {
        val lines = java.nio.file.Files.readAllLines(path, StandardCharsets.UTF_8)
        val resolved = mutableListOf<String>()
        var index = 0
        var foundConflict = false
        while (index < lines.size) {
            if (!lines[index].startsWith("<<<<<<<")) {
                resolved.add(lines[index++])
                continue
            }
            foundConflict = true
            index++
            val current = mutableListOf<String>()
            while (index < lines.size && !lines[index].startsWith("=======")) current.add(lines[index++])
            if (index >= lines.size) return false
            index++
            val incoming = mutableListOf<String>()
            while (index < lines.size && !lines[index].startsWith(">>>>>>>")) incoming.add(lines[index++])
            if (index >= lines.size || !isVersionOnlyFragment(current) || !isVersionOnlyFragment(incoming)) return false
            index++
            // During both rebase and stash application, the current side contains
            // the version based on the newly fetched develop branch.
            resolved.addAll(current)
        }
        if (foundConflict) java.nio.file.Files.write(path, resolved, StandardCharsets.UTF_8)
        return foundConflict
    }

    private fun isVersionOnlyFragment(lines: List<String>): Boolean {
        val meaningful = lines.filter { it.isNotBlank() }
        return meaningful.isNotEmpty() && meaningful.all { VERSION_ELEMENT.matches(it) }
    }

    private fun syncDevelopRepository(
        git: Git,
        repoDir: File,
        currentBranch: String,
        mergeDevelop: Boolean
    ): String? {
        val hasDevelop = git.branchList().call().any { it.name == LOCAL_DEVELOP_REF }
        if (!hasDevelop) {
            logger.info("Branch 'develop' not found locally for ${repoDir.absolutePath}. Skipping.")
            return "Branch 'develop' not found locally for ${repoDir.name}. Skipped."
        }
        git.checkout().setName("develop").call()
        git.pull().setRemote("origin").setRemoteBranchName("develop")
            .setCredentialsProvider(CredentialsProvider.getDefault()).call()
        git.checkout().setName(currentBranch).call()
        if (!mergeDevelop || currentBranch == "develop") return null

        logger.info("Merging develop into $currentBranch for ${repoDir.absolutePath}")
        val result = mergeIntoCurrent(git, git.repository.findRef("develop"), currentBranch)
        if (result.mergeStatus.isSuccessful) return null
        logger.warn("Merge develop into $currentBranch failed for ${repoDir.absolutePath}: ${result.mergeStatus}")
        return "Sync completed, but merge into $currentBranch failed with status: ${result.mergeStatus}. You may have conflicts."
    }

    private fun restoreBranch(git: Git, branch: String) {
        try {
            if (git.repository.branch != branch) git.checkout().setName(branch).call()
        } catch (e: Exception) {
            logger.error("Failed to switch back to original branch $branch: ${e.message}")
        }
    }

    internal fun mergeIntoCurrent(git: Git, sourceRef: Ref, currentBranch: String): MergeResult {
        val result = git.merge()
            .include(sourceRef)
            .setCommit(false)
            .call()

        if (result.mergeStatus == MergeResult.MergeStatus.MERGED_NOT_COMMITTED) {
            val repository = git.repository
            val identity = resolveCommitIdentity(repository)
            val sourceBranch = sourceRef.name.substringAfterLast('/')
            val message = repository.readMergeCommitMsg()?.takeIf { it.isNotBlank() }
                ?: "Merge branch '$sourceBranch' into $currentBranch"

            git.commit()
                .setMessage(message)
                .setAuthor(identity)
                .setCommitter(identity)
                .setSign(false)
                .call()
        }

        return result
    }

    internal fun resolveCommitIdentity(repository: Repository): PersonIdent {
        val defaultIdentity = PersonIdent(repository)
        val configuredName = repository.config.getString("user", null, "name")?.takeIf { it.isNotBlank() }
        val configuredEmail = repository.config.getString("user", null, "email")?.takeIf { it.isNotBlank() }

        return if (configuredName == null && configuredEmail == null) {
            defaultIdentity
        } else {
            PersonIdent(
                configuredName ?: defaultIdentity.name,
                configuredEmail ?: defaultIdentity.emailAddress
            )
        }
    }

    fun getGitStatus(projectPath: File): GitStatus? {
        val repoDir = findGitRoot(projectPath) ?: return null
        return try {
            Git.open(repoDir).use { git ->
                val repository = git.repository
                val currentBranch = repository.branch ?: return null
                val develop = repository.resolve("develop") ?: return GitStatus(currentBranch, 0, 0)
                val head = repository.resolve("HEAD") ?: return GitStatus(currentBranch, 0, 0)

                val walk = RevWalk(repository)
                try {
                    val headCommit = walk.parseCommit(head)
                    val developCommit = walk.parseCommit(develop)

                    // Behind: in develop but not in head
                    walk.reset()
                    walk.markStart(developCommit)
                    walk.markUninteresting(headCommit)
                    var behind = 0
                    for (commit in walk) {
                        behind++
                    }

                    // Ahead: in head but not in develop
                    walk.reset()
                    walk.markStart(headCommit)
                    walk.markUninteresting(developCommit)
                    var ahead = 0
                    for (commit in walk) {
                        ahead++
                    }

                    GitStatus(currentBranch, ahead, behind)
                } finally {
                    walk.dispose()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to get git status for $projectPath: ${e.message}")
            null
        }
    }

    fun getLatestTagInfo(projectPath: File): TagInfo? {
        val repoDir = findGitRoot(projectPath) ?: return null
        val normalizedProjectPath = projectPath.toPath().toAbsolutePath().normalize().toString()
        if (tagCache.containsKey(normalizedProjectPath)) {
            return tagCache[normalizedProjectPath].let { if (it === noTag) null else it as TagInfo }
        }
        val gitRootPath = repoDir.toPath().toAbsolutePath().normalize()
        val pomPath = projectPath.toPath().toAbsolutePath().normalize()
        val relativePomPath = gitRootPath.relativize(pomPath).toString().replace(File.separator, "/")
        val result = try {
            Git.open(repoDir).use { git ->
                fetchTagsOnce(git, repoDir)
                resolveLatestTag(git, repoDir, relativePomPath)
            }
        } catch (e: Exception) {
            logger.warn("Failed to get latest tag version for $projectPath: ${e.message}")
            null
        }
        tagCache[normalizedProjectPath] = result ?: noTag
        return result
    }

    private fun fetchTagsOnce(git: Git, repoDir: File) {
        if (!fetchedRepos.add(repoDir)) return
        try {
            logger.info("Fetching tags for ${repoDir.absolutePath}")
            git.fetch().setRemote("origin").setTagOpt(TagOpt.FETCH_TAGS)
                .setCredentialsProvider(CredentialsProvider.getDefault()).call()
        } catch (e: Exception) {
            logger.warn("Could not fetch tags from origin for ${repoDir.absolutePath}: ${e.message}")
        }
    }

    private fun resolveLatestTag(git: Git, repoDir: File, relativePomPath: String): TagInfo? {
        val tags = repoTagsCache.getOrPut(repoDir) { loadTags(git, repoDir) }
        if (tags.isEmpty()) return null
        val latest = findLatestTag(git.repository, tags) ?: return null
        logger.info("Latest tag in repo is ${latest.tagName} on commit ${latest.commit.name.take(7)}")
        val version = readPomVersionFromCommit(git.repository, latest.commit, relativePomPath) ?: return null
        logger.info("Version found for $relativePomPath in tag ${latest.tagName}: $version")
        return TagInfo(version, latest.tagName.substringAfter("refs/tags/"))
    }

    private fun loadTags(git: Git, repoDir: File): List<Ref> {
        val tags = git.tagList().call().toList()
        if (tags.isEmpty()) logger.info("No tags found in repository ${repoDir.absolutePath}")
        else logger.info("Found ${tags.size} tags in repository ${repoDir.absolutePath}. First few: ${tags.take(5).map { it.name }}")
        return tags
    }

    private fun findLatestTag(repository: Repository, tags: List<Ref>): TagCommitInfo? {
        RevWalk(repository).use { walk ->
            return tags.mapNotNull { ref -> tagCommitInfo(walk, ref) }.maxByOrNull { it.commitTime }
        }
    }

    private fun tagCommitInfo(walk: RevWalk, ref: Ref): TagCommitInfo? = try {
        val obj = walk.parseAny(ref.objectId)
        val commit = when (obj) {
            is RevTag -> walk.parseCommit(obj.getObject())
            is RevCommit -> obj
            else -> null
        }
        commit?.let { TagCommitInfo(ref.name, it.commitTime, it) }
    } catch (e: Exception) {
        null
    }

    private data class TagCommitInfo(val tagName: String, val commitTime: Int, val commit: RevCommit)

    private fun readPomVersionFromCommit(repository: Repository, commit: RevCommit, relativePomPath: String): String? {
        val tree = commit.tree
        TreeWalk(repository).use { treeWalk ->
            treeWalk.addTree(tree)
            treeWalk.isRecursive = true
            while (treeWalk.next()) {
                if (treeWalk.pathString == relativePomPath) {
                    val objectId = treeWalk.getObjectId(0)
                    val loader = repository.open(objectId)
                    loader.openStream().use { inputStream ->
                        val reader = MavenXpp3Reader()
                        InputStreamReader(inputStream, StandardCharsets.UTF_8).use { readerStream ->
                            val model = reader.read(readerStream)
                            return model.version ?: model.parent?.version
                        }
                    }
                }
            }
        }
        return null
    }

    internal fun findGitRoot(dir: File): File? {
        var current: File? = if (dir.isFile) dir.parentFile else dir
        while (current != null) {
            val gitDir = File(current, ".git")
            if (gitDir.exists() && gitDir.isDirectory) {
                return current
            }
            current = current.parentFile
        }
        return null
    }

    private companion object {
        const val MAX_AUTOMATIC_REBASE_CONTINUES = 100
        const val REMOTE_DEVELOP_REF = "refs/remotes/origin/develop"
        const val LOCAL_DEVELOP_REF = "refs/heads/develop"
        const val CONFIG_COMMIT_SECTION = "commit"
        const val COMMIT_GPG_SIGN = "gpgSign"
        val PROTECTED_BRANCHES = setOf("main", "master", "develop")
        val VERSION_ELEMENT = Regex(
            """\s*<((?:[A-Za-z0-9_.-]*version)|revision|changelist|sha1)>.*</\1>\s*""",
            RegexOption.IGNORE_CASE
        )
    }
}
