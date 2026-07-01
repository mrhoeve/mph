package nl.hicts.mph.services

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File

@Service
class GitService {
    private val logger = LoggerFactory.getLogger(GitService::class.java)

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
                    git.pull().setRemote("origin").call()
                } catch (e: Exception) {
                    logger.warn("Could not perform git pull origin: ${e.message}")
                    // Fallback to just fetching if pull fails
                    try {
                        git.fetch().setRemote("origin").call()
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
                git.pull().setRemote("origin").setRemoteBranchName("develop").call()

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
