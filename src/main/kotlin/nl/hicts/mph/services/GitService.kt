package nl.hicts.mph.services

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
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
    
    private val tagCache = mutableMapOf<String, String?>() // path -> version
    private val repoTagsCache = mutableMapOf<File, List<org.eclipse.jgit.lib.Ref>>()
    private val fetchedRepos = mutableSetOf<File>()

    fun clearCache() {
        tagCache.clear()
        repoTagsCache.clear()
        fetchedRepos.clear()
    }

    fun prepareBranch(projectPath: String, branchName: String) {
        if (branchName.isBlank()) return

        val repoDir = findGitRoot(File(projectPath)) ?: run {
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
                val localBranches = git.branchList().call()
                val existsLocally = localBranches.any { it.name == "refs/heads/$branchName" }

                if (existsLocally) {
                    logger.info("Branch '$branchName' exists locally, checking it out.")
                    git.checkout()
                        .setName(branchName)
                        .call()
                } else {
                    val remoteBranches = git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call()
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

    fun syncDevelop(projectPath: String): String? {
        val repoDir = findGitRoot(File(projectPath)) ?: run {
            logger.warn("No Git repository found for path: $projectPath")
            return null
        }

        Git.open(repoDir).use { git ->
            val repository = git.repository
            val currentBranch = repository.branch
            logger.info("Syncing develop for ${repoDir.absolutePath}. Current branch: $currentBranch")

            try {
                // 1. Check if develop exists locally
                val localBranches = git.branchList().call()
                val existsLocally = localBranches.any { it.name == "refs/heads/develop" }

                if (!existsLocally) {
                    logger.info("Branch 'develop' not found locally for ${repoDir.absolutePath}. Skipping.")
                    return "Branch 'develop' not found locally for ${repoDir.name}. Skipped."
                }

                // 2. Checkout develop
                git.checkout().setName("develop").call()

                // 3. Pull origin develop
                git.pull().setRemote("origin").setRemoteBranchName("develop").setCredentialsProvider(CredentialsProvider.getDefault()).call()

                // 4. Switch back to original branch
                git.checkout().setName(currentBranch).call()
                return null
            } catch (e: Exception) {
                logger.error("Sync develop failed for ${repoDir.absolutePath}: ${e.message}", e)
                // Try to switch back if we are not on the original branch
                try {
                    if (git.repository.branch != currentBranch) {
                        git.checkout().setName(currentBranch).call()
                    }
                } catch (ce: Exception) {
                    logger.error("Failed to switch back to original branch $currentBranch: ${ce.message}")
                }
                throw RuntimeException("Sync develop failed: ${e.message}")
            }
        }
    }

    fun getLatestTag(projectPath: String): String? {
        val repoDir = findGitRoot(File(projectPath)) ?: return null
        val normalizedProjectPath = File(projectPath).toPath().toAbsolutePath().normalize().toString()
        
        if (tagCache.containsKey(normalizedProjectPath)) {
            return tagCache[normalizedProjectPath]
        }

        val gitRootPath = repoDir.toPath().toAbsolutePath().normalize()
        val pomPath = File(projectPath).toPath().toAbsolutePath().normalize()
        val relativePomPath = gitRootPath.relativize(pomPath).toString().replace(File.separator, "/")

        return try {
            Git.open(repoDir).use { git ->
                // 1. Fetch tags from origin once per repo
                if (!fetchedRepos.contains(repoDir)) {
                    try {
                        logger.info("Fetching tags for ${repoDir.absolutePath}")
                        git.fetch().setRemote("origin").setTagOpt(TagOpt.FETCH_TAGS).setCredentialsProvider(CredentialsProvider.getDefault()).call()
                    } catch (e: Exception) {
                        logger.warn("Could not fetch tags from origin for ${repoDir.absolutePath}: ${e.message}")
                    }
                    fetchedRepos.add(repoDir)
                }

                val tags = repoTagsCache.getOrPut(repoDir) {
                    git.tagList().call()
                }
                
                if (tags.isEmpty()) {
                    tagCache[normalizedProjectPath] = null
                    return null
                }

                val repository = git.repository
                val walk = RevWalk(repository)
                try {
                    val tagWithCommit = tags.mapNotNull { ref ->
                        try {
                            val obj = walk.parseAny(ref.objectId)
                            val commit = if (obj is RevTag) {
                                walk.parseCommit(obj.getObject())
                            } else if (obj is RevCommit) {
                                obj
                            } else {
                                null
                            }
                            commit?.let { it to it.commitTime }
                        } catch (e: Exception) {
                            null
                        }
                    }
                    val latestCommit = tagWithCommit.maxByOrNull { it.second }?.first 
                    
                    val version = if (latestCommit != null) {
                        // 2. Read the specific pom.xml from this commit
                        readPomVersionFromCommit(repository, latestCommit, relativePomPath)
                    } else null
                    
                    tagCache[normalizedProjectPath] = version
                    version
                } finally {
                    walk.dispose()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to get latest tag version for $projectPath: ${e.message}")
            tagCache[normalizedProjectPath] = null
            null
        }
    }

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
