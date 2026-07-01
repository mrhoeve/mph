package nl.hicts.mph.services

import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Assertions.assertEquals
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

            // 4. Verify getLatestTag returns 1.2.3 (from the tag)
            // Note: fetch origin will fail in this local-only test, but the code handles it
            val version = gitService.getLatestTag(pomFile.absolutePath)
            assertEquals("1.2.3", version)
        }
    }
}
