package nl.hicts.mph.services

import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class GitServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val gitService = GitService()

    @Test
    fun `should skip develop synchronization when the branch does not exist`() {
        val repoDir = tempDir.resolve("without-develop").toFile().apply { mkdirs() }
        Git.init().setDirectory(repoDir).call().use { git ->
            File(repoDir, "test.txt").writeText("test")
            git.add().addFilepattern("test.txt").call()
            git.commit().setMessage("initial commit").setSign(false).call()

            val message = gitService.syncDevelop(repoDir)

            assertTrue(message.orEmpty().contains("Branch 'develop' not found"))
            assertEquals(git.repository.branch, "master")
        }
    }

    @Test
    fun `should never rebase a protected branch`() {
        val repoDir = tempDir.resolve("protected-branch").toFile().apply { mkdirs() }
        Git.init().setDirectory(repoDir).call().use { git ->
            File(repoDir, "test.txt").writeText("test")
            git.add().addFilepattern("test.txt").call()
            git.commit().setMessage("initial commit").setSign(false).call()

            val result = gitService.rebaseOnDevelop(repoDir)

            assertEquals(DevelopRebaseStatus.SKIPPED, result.status)
            assertTrue(result.message.contains("protected"))
            assertEquals("master", git.repository.branch)
        }
    }

    @Test
    fun `should report missing repositories and remotes without changing files`() {
        val plainDirectory = tempDir.resolve("plain-directory").toFile().apply { mkdirs() }
        val missingRepository = gitService.rebaseOnDevelop(plainDirectory)
        assertEquals(DevelopRebaseStatus.SKIPPED, missingRepository.status)

        val repoDir = tempDir.resolve("missing-origin").toFile().apply { mkdirs() }
        Git.init().setDirectory(repoDir).call().use { git ->
            File(repoDir, "test.txt").writeText("test")
            git.add().addFilepattern("test.txt").call()
            git.commit().setMessage("initial commit").setSign(false).call()
            git.checkout().setCreateBranch(true).setName("feature/test").call()

            val missingRemote = gitService.rebaseOnDevelop(repoDir)

            assertEquals(DevelopRebaseStatus.FAILED, missingRemote.status)
            assertTrue(missingRemote.message.contains("Rebase failed"))
            assertEquals("test", File(repoDir, "test.txt").readText())
        }
    }

    @Test
    fun `should skip detached heads and remotes without develop`() {
        val detachedDir = tempDir.resolve("detached").toFile().apply { mkdirs() }
        Git.init().setDirectory(detachedDir).call().use { git ->
            File(detachedDir, "test.txt").writeText("test")
            git.add().addFilepattern("test.txt").call()
            val commit = git.commit().setMessage("initial").setSign(false).call()
            git.checkout().setName(commit.name).call()

            val detached = gitService.rebaseOnDevelop(detachedDir)

            assertEquals(DevelopRebaseStatus.SKIPPED, detached.status)
            assertTrue(detached.message.contains("detached HEAD"))
        }

        val remoteDir = tempDir.resolve("empty-remote.git").toFile().apply { mkdirs() }
        Git.init().setBare(true).setDirectory(remoteDir).call().use { remote ->
            val localDir = tempDir.resolve("remote-without-develop").toFile().apply { mkdirs() }
            Git.init().setDirectory(localDir).call().use { git ->
                git.repository.config.apply {
                    setString("remote", "origin", "url", remote.repository.directory.toURI().toString())
                    setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
                    save()
                }
                File(localDir, "test.txt").writeText("test")
                git.add().addFilepattern("test.txt").call()
                git.commit().setMessage("initial").setSign(false).call()
                git.checkout().setCreateBranch(true).setName("feature/test").call()

                val noDevelop = gitService.rebaseOnDevelop(localDir)

                assertEquals(DevelopRebaseStatus.SKIPPED, noDevelop.status)
                assertTrue(noDevelop.message.contains("origin/develop was not found"))
            }
        }
    }

    @Test
    fun `should initialize a missing local develop and reject a diverged one`() {
        val missingLocal = createRemoteFixture("missing-local-develop")
        missingLocal.local.use { git ->
            val repoDir = git.repository.workTree
            git.checkout().setCreateBranch(true).setName("feature/test").call()
            git.branchDelete().setBranchNames("develop").setForce(true).call()

            val result = gitService.rebaseOnDevelop(repoDir)

            assertEquals(DevelopRebaseStatus.SUCCESS, result.status)
            assertEquals(
                git.repository.resolve("refs/remotes/origin/develop"),
                git.repository.resolve("refs/heads/develop")
            )
        }
        missingLocal.remote.close()

        val diverged = createRemoteFixture("diverged-local-develop")
        diverged.local.use { git ->
            val repoDir = git.repository.workTree
            git.checkout().setCreateBranch(true).setName("feature/test").call()
            git.checkout().setName("develop").call()
            File(repoDir, "local-develop.txt").writeText("local only")
            git.add().addFilepattern("local-develop.txt").call()
            git.commit().setMessage("local develop commit").setSign(false).call()
            git.checkout().setName("feature/test").call()

            val result = gitService.rebaseOnDevelop(repoDir)

            assertEquals(DevelopRebaseStatus.SKIPPED, result.status)
            assertTrue(result.message.contains("Local develop has commits"))
        }
        diverged.remote.close()
    }

    @Test
    fun `should restore the original branch when develop synchronization fails`() {
        val repoDir = tempDir.resolve("failing-develop").toFile().apply { mkdirs() }
        Git.init().setDirectory(repoDir).call().use { git ->
            File(repoDir, "test.txt").writeText("test")
            git.add().addFilepattern("test.txt").call()
            git.commit().setMessage("initial commit").setSign(false).call()
            git.branchCreate().setName("develop").call()

            val error = assertThrows(RuntimeException::class.java) {
                gitService.syncDevelop(repoDir, mergeIntoCurrent = true)
            }

            assertTrue(error.message.orEmpty().contains("Sync develop failed"))
            assertEquals("master", git.repository.branch)
        }
    }

    @Test
    fun `should prepare branch correctly`() {
        val repoDir = tempDir.toFile()
        Git.init().setDirectory(repoDir).call().use { git ->
            // Create a commit so we have a HEAD
            File(repoDir, "test.txt").writeText("test")
            git.add().addFilepattern("test.txt").call()
            git.commit().setMessage("initial commit").setSign(false).call()

            // Prepare a new branch
            gitService.prepareBranch(repoDir, "feature-test")

            val branches = git.branchList().call()
            assertTrue(branches.any { it.name == "refs/heads/feature-test" })
            assertEquals("feature-test", git.repository.branch)
        }
    }

    @Test
    fun `should get latest tag version from pom`() {
        val repoDir = tempDir.toFile()
        Git.init().setDirectory(repoDir).call().use { git ->
            // 1. Create pom.xml
            val pomFile = File(repoDir, "pom.xml")
            pomFile.writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>test</artifactId>
                    <version>1.2.3</version>
                </project>
            """.trimIndent())
            
            git.add().addFilepattern("pom.xml").call()
            val commit = git.commit().setMessage("add pom").setSign(false).call()
            
            // 2. Create a tag
            git.tag().setName("v1.2.3").setObjectId(commit).call()
            
            // 3. Update pom in next commit
            pomFile.writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>test</artifactId>
                    <version>1.2.4-SNAPSHOT</version>
                </project>
            """.trimIndent())
            git.add().addFilepattern("pom.xml").call()
            git.commit().setMessage("bump version").setSign(false).call()

            // 4. Verify getLatestTagInfo returns 1.2.3 (from the tag)
            // Note: fetch origin will fail in this local-only test, but the code handles it
            val info = gitService.getLatestTagInfo(pomFile)
            assertEquals("1.2.3", info?.version)
            assertEquals("v1.2.3", info?.tagName)
        }
    }

    @Test
    fun `should return null when no tags exist`() {
        val repoDir = tempDir.resolve("no-tags").toFile()
        repoDir.mkdirs()
        Git.init().setDirectory(repoDir).call().use { git ->
            val pomFile = File(repoDir, "pom.xml")
            pomFile.writeText("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>test</groupId>
                    <artifactId>test</artifactId>
                    <version>1.0.0</version>
                </project>
            """.trimIndent())
            git.add().addFilepattern("pom.xml").call()
            git.commit().setMessage("initial commit").setSign(false).call()

            val info = gitService.getLatestTagInfo(pomFile)
            assertTrue(info == null, "Expected null info when no tags exist, but got $info")
        }
    }

    @Test
    fun `should get git status correctly`() {
        val repoDir = tempDir.resolve("status-repo").toFile()
        repoDir.mkdirs()
        Git.init().setDirectory(repoDir).call().use { git ->
            File(repoDir, "base.txt").writeText("base")
            git.add().addFilepattern("base.txt").call()
            val baseCommit = git.commit().setMessage("initial").setSign(false).call()

            // Create develop branch
            git.branchCreate().setName("develop").setStartPoint(baseCommit).call()

            // Add a commit to develop
            git.checkout().setName("develop").call()
            File(repoDir, "dev.txt").writeText("dev")
            git.add().addFilepattern("dev.txt").call()
            git.commit().setMessage("dev commit 1").setSign(false).call()
            File(repoDir, "dev2.txt").writeText("dev2")
            git.add().addFilepattern("dev2.txt").call()
            git.commit().setMessage("dev commit 2").setSign(false).call()

            // Go back to master (default branch)
            git.checkout().setName("master").call()
            File(repoDir, "master.txt").writeText("master")
            git.add().addFilepattern("master.txt").call()
            git.commit().setMessage("master commit 1").setSign(false).call()

            // Status: master is 1 ahead, 2 behind develop
            val status = gitService.getGitStatus(repoDir)
            assertEquals("master", status?.branchName)
            assertEquals(1, status?.aheadCount)
            assertEquals(2, status?.behindCount)
        }
    }

    @Test
    fun `should preserve configured identity exactly in merge commit`() {
        val repoDir = tempDir.resolve("merge-identity").toFile()
        repoDir.mkdirs()

        Git.init().setDirectory(repoDir).call().use { git ->
            git.repository.config.apply {
                setString("user", null, "name", "Test User")
                setString("user", null, "email", "Test.User@example.org")
                save()
            }

            File(repoDir, "base.txt").writeText("base")
            git.add().addFilepattern("base.txt").call()
            val baseCommit = git.commit().setMessage("initial").setSign(false).call()

            git.branchCreate().setName("develop").setStartPoint(baseCommit).call()
            git.checkout().setName("develop").call()
            File(repoDir, "develop.txt").writeText("develop")
            git.add().addFilepattern("develop.txt").call()
            git.commit().setMessage("develop change").setSign(false).call()

            git.checkout().setName("master").call()
            File(repoDir, "feature.txt").writeText("feature")
            git.add().addFilepattern("feature.txt").call()
            git.commit().setMessage("feature change").setSign(false).call()

            val result = gitService.mergeIntoCurrent(git, git.repository.findRef("develop"), "master")
            val mergeCommits = git.log().setMaxCount(1).call().toList()

            assertTrue(result.mergeStatus.isSuccessful)
            assertEquals(1, mergeCommits.size)
            val mergeCommit = mergeCommits.single()
            assertEquals(2, mergeCommit.parentCount)
            assertEquals("Test User", mergeCommit.authorIdent.name)
            assertEquals("Test.User@example.org", mergeCommit.authorIdent.emailAddress)
            assertEquals("Test User", mergeCommit.committerIdent.name)
            assertEquals("Test.User@example.org", mergeCommit.committerIdent.emailAddress)
        }
    }

    @Test
    fun `should use git defaults for missing identity values`() {
        val repoDir = tempDir.resolve("partial-identity").toFile()
        repoDir.mkdirs()

        Git.init().setDirectory(repoDir).call().use { git ->
            val defaultIdentity = org.eclipse.jgit.lib.PersonIdent(git.repository)
            git.repository.config.apply {
                setString("user", null, "name", "Exact Name")
                unset("user", null, "email")
                save()
            }

            val identity = gitService.resolveCommitIdentity(git.repository)

            assertEquals("Exact Name", identity.name)
            assertEquals(defaultIdentity.emailAddress, identity.emailAddress)
        }
    }

    @Test
    fun `cache can be cleared while tag information is read concurrently`() {
        val repoDir = tempDir.resolve("concurrent-cache").toFile().also { it.mkdirs() }
        Git.init().setDirectory(repoDir).call().use { git ->
            val pomFile = File(repoDir, "pom.xml")
            pomFile.writeText("""
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>org.example</groupId>
                    <artifactId>sample</artifactId>
                    <version>1.0.0</version>
                </project>
            """.trimIndent())
            git.add().addFilepattern("pom.xml").call()
            val commit = git.commit().setMessage("initial").setSign(false).call()
            git.tag().setName("v1.0.0").setObjectId(commit).call()

            val executor = Executors.newFixedThreadPool(6)
            try {
                val tasks = (1..30).map { index ->
                    Callable {
                        if (index % 3 == 0) gitService.clearCache()
                        gitService.getLatestTagInfo(pomFile)?.version
                    }
                }

                val versions = executor.invokeAll(tasks).map { it.get() }.filterNotNull()
                assertTrue(versions.isNotEmpty())
                assertTrue(versions.all { it == "1.0.0" })
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `should resolve only version conflict hunks using the current version`() {
        val versionPom = tempDir.resolve("version-pom.xml")
        versionPom.toFile().writeText(
            """
            <project>
            <<<<<<< HEAD
              <version>4.1-SNAPSHOT</version>
            =======
              <version>PREFIX-1234-4.0-SNAPSHOT</version>
            >>>>>>> stash
            </project>
            """.trimIndent()
        )

        assertTrue(gitService.resolveVersionConflictFile(versionPom))
        assertTrue(versionPom.toFile().readText().contains("<version>4.1-SNAPSHOT</version>"))
        assertFalse(versionPom.toFile().readText().contains("PREFIX-1234"))

        val structuralPom = tempDir.resolve("structural-pom.xml")
        val original = """
            <project>
            <<<<<<< HEAD
              <artifactId>develop-name</artifactId>
            =======
              <artifactId>feature-name</artifactId>
            >>>>>>> feature
            </project>
        """.trimIndent()
        structuralPom.toFile().writeText(original)

        assertFalse(gitService.resolveVersionConflictFile(structuralPom))
        assertEquals(original, structuralPom.toFile().readText())
    }

    @Test
    fun `should rebase version conflicts and restore tracked and untracked work`() {
        val fixture = createRemoteFixture("successful-rebase")
        fixture.local.use { git ->
            val repoDir = git.repository.workTree
            val pom = File(repoDir, "pom.xml")
            val source = File(repoDir, "source.txt")

            git.branchCreate().setName("feature/upgrade").call()
            git.checkout().setName("develop").call()
            pom.writeText(simplePom("4.1-SNAPSHOT"))
            git.add().addFilepattern("pom.xml").call()
            git.commit().setMessage("develop version").setSign(false).call()
            git.push().setRemote("origin").add("develop").call()

            git.checkout().setName("feature/upgrade").call()
            pom.writeText(simplePom("PREFIX-1234-4.0-SNAPSHOT"))
            git.add().addFilepattern("pom.xml").call()
            git.commit().setMessage("prefix feature version").setSign(false).call()
            source.writeText("uncommitted source work")
            File(repoDir, "untracked.txt").writeText("untracked work")

            val result = gitService.rebaseOnDevelop(pom)

            assertEquals(DevelopRebaseStatus.SUCCESS, result.status)
            assertEquals("feature/upgrade", git.repository.branch)
            assertTrue(pom.readText().contains("<version>4.1-SNAPSHOT</version>"))
            assertEquals("uncommitted source work", source.readText())
            assertEquals("untracked work", File(repoDir, "untracked.txt").readText())
            assertTrue(git.stashList().call().isEmpty())
            assertFalse(git.status().call().isClean)
            assertFalse(git.repository.config.getNames("commit", null, false).contains("gpgSign"))
            assertEquals(
                git.repository.resolve("refs/remotes/origin/develop"),
                git.repository.resolve("refs/heads/develop")
            )
        }
        fixture.remote.close()
    }

    @Test
    fun `should leave source conflicts in rebase state and preserve the stash`() {
        val fixture = createRemoteFixture("conflicted-rebase")
        fixture.local.use { git ->
            val repoDir = git.repository.workTree
            val source = File(repoDir, "source.txt")

            git.branchCreate().setName("feature/upgrade").call()
            git.checkout().setName("develop").call()
            source.writeText("develop source")
            git.add().addFilepattern("source.txt").call()
            git.commit().setMessage("develop source change").setSign(false).call()
            git.push().setRemote("origin").add("develop").call()

            git.checkout().setName("feature/upgrade").call()
            source.writeText("feature source")
            git.add().addFilepattern("source.txt").call()
            git.commit().setMessage("feature source change").setSign(false).call()
            File(repoDir, "untracked.txt").writeText("preserve me")

            val result = gitService.rebaseOnDevelop(File(repoDir, "pom.xml"))

            assertEquals(DevelopRebaseStatus.CONFLICT, result.status)
            assertTrue(result.stashPreserved)
            assertTrue(git.repository.repositoryState.isRebasing)
            assertTrue(git.status().call().conflicting.contains("source.txt"))
            assertEquals(1, git.stashList().call().size)
        }
        fixture.remote.close()
    }

    @Test
    fun `should preserve stash when uncommitted source work conflicts after a successful rebase`() {
        val fixture = createRemoteFixture("stash-conflicted-rebase")
        fixture.local.use { git ->
            val repoDir = git.repository.workTree
            val source = File(repoDir, "source.txt")

            git.branchCreate().setName("feature/upgrade").call()
            git.checkout().setName("develop").call()
            source.writeText("develop source")
            git.add().addFilepattern("source.txt").call()
            git.commit().setMessage("develop source change").setSign(false).call()
            git.push().setRemote("origin").add("develop").call()

            git.checkout().setName("feature/upgrade").call()
            source.writeText("uncommitted feature source")

            val result = gitService.rebaseOnDevelop(File(repoDir, "pom.xml"))

            assertEquals(DevelopRebaseStatus.CONFLICT, result.status)
            assertTrue(result.stashPreserved)
            assertFalse(git.repository.repositoryState.isRebasing)
            assertTrue(git.status().call().conflicting.contains("source.txt"))
            assertEquals(1, git.stashList().call().size)
        }
        fixture.remote.close()
    }

    private fun createRemoteFixture(name: String): GitFixture {
        val remoteDir = tempDir.resolve("$name-remote.git").toFile().apply { mkdirs() }
        val remote = Git.init().setBare(true).setDirectory(remoteDir).call()
        val localDir = tempDir.resolve("$name-local").toFile().apply { mkdirs() }
        val local = Git.init().setDirectory(localDir).call()
        local.repository.config.apply {
            setString("user", null, "name", "Test User")
            setString("user", null, "email", "test.user@example.org")
            setString("remote", "origin", "url", remoteDir.toURI().toString())
            setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
            save()
        }
        File(localDir, "pom.xml").writeText(simplePom("4.0-SNAPSHOT"))
        File(localDir, "source.txt").writeText("base source")
        local.add().addFilepattern(".").call()
        local.commit().setMessage("initial").setSign(false).call()
        local.branchCreate().setName("develop").call()
        local.push().setRemote("origin").add("develop").call()
        return GitFixture(local, remote)
    }

    private fun simplePom(version: String) = """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>org.example</groupId>
          <artifactId>test-project</artifactId>
          <version>$version</version>
        </project>
    """.trimIndent()

    private data class GitFixture(val local: Git, val remote: Git)
}
