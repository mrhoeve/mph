package nl.hicts.mph.services

import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class GitServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val gitService = GitService()

    @Test
    fun `should prepare branch correctly`() {
        val repoDir = tempDir.toFile()
        Git.init().setDirectory(repoDir).call().use { git ->
            // Create a commit so we have a HEAD
            File(repoDir, "test.txt").writeText("test")
            git.add().addFilepattern("test.txt").call()
            git.commit().setMessage("initial commit").setSign(false).call()

            // Prepare a new branch
            gitService.prepareBranch(repoDir.absolutePath, "feature-test")

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
            val info = gitService.getLatestTagInfo(pomFile.absolutePath)
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

            val info = gitService.getLatestTagInfo(pomFile.absolutePath)
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
            val status = gitService.getGitStatus(repoDir.absolutePath)
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
            val mergeCommit = git.log().setMaxCount(1).call().firstOrNull()

            assertTrue(result.mergeStatus.isSuccessful)
            assertNotNull(mergeCommit)
            assertEquals(2, mergeCommit!!.parentCount)
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
}
