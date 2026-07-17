package nl.hicts.mph.services

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
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
import java.util.concurrent.ConcurrentHashMap

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

    private fun syncDevelopRepository(
        git: Git,
        repoDir: File,
        currentBranch: String,
        mergeDevelop: Boolean
    ): String? {
        val hasDevelop = git.branchList().call().any { it.name == "refs/heads/develop" }
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

    private fun findGitRoot(dir: File): File? {
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
}
