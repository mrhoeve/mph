package nl.hicts.mph.intellij.services

import nl.hicts.mph.intellij.model.MavenProjectInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class GitWorkspaceServiceTest {
    @Test
    fun `parses ahead and behind counts`() {
        assertEquals(3 to 7, GitOutputParser.aheadBehind("3\t7\n"))
        assertNull(GitOutputParser.aheadBehind("unavailable"))
    }

    @Test
    fun `validates branch names before invoking Git`() {
        assertTrue(GitOutputParser.validBranchName("feature/spring-boot-4"))
        assertFalse(GitOutputParser.validBranchName("feature branch"))
        assertFalse(GitOutputParser.validBranchName("../main"))
        assertFalse(GitOutputParser.validBranchName("topic.lock."))
    }

    @Test
    fun `reports the current branch relative to origin develop`() {
        withRepository("mph-git-status-") { repository ->
            commit(repository, "base.txt", "base", "Initial commit")
            git(repository, "update-ref", "refs/remotes/origin/develop", "HEAD")
            git(repository, "switch", "--create", "feature/status")
            commit(repository, "feature.txt", "feature", "Feature commit")

            val status = GitWorkspaceService().status(repository.toString())

            assertEquals("feature/status", status?.branchName)
            assertEquals(1, status?.aheadCount)
            assertEquals(0, status?.behindCount)

            git(repository, "switch", "--detach")
            assertNull(GitWorkspaceService().status(repository.toString()))
        }
    }

    @Test
    fun `reads the project version from the latest local tag`() {
        withRepository("mph-git-tags-") { repository ->
            val pom = repository.resolve("pom.xml")
            Files.writeString(pom, pom("1.2.3"))
            git(repository, "add", "pom.xml")
            git(repository, "commit", "-m", "Release 1.2.3")
            git(repository, "tag", "v1.2.3")
            Files.writeString(pom, pom("1.3.0-SNAPSHOT"))
            git(repository, "add", "pom.xml")
            git(repository, "commit", "-m", "Start next development version")

            val result = GitWorkspaceService().latestVersion(project(pom, repository))

            assertEquals("v1.2.3", result?.tagName)
            assertEquals("1.2.3", result?.version)
            assertNull(GitWorkspaceService().latestVersion(project(pom, repository, gitRootPath = null)))
        }
    }

    @Test
    fun `creates and checks out local and remote branches`() {
        val testRoot = Files.createTempDirectory("mph-git-branches-")
        val origin = testRoot.resolve("origin.git")
        val repository = testRoot.resolve("workspace")
        try {
            git(testRoot, "init", "--bare", origin.toString())
            Files.createDirectory(repository)
            configureRepository(repository)
            commit(repository, "base.txt", "base", "Initial commit")
            git(repository, "remote", "add", "origin", origin.toString())
            git(repository, "push", "--set-upstream", "origin", "develop")

            val service = GitWorkspaceService()
            val created = service.createOrCheckoutBranch(listOf(repository.toString()), "feature/created").single()
            assertTrue(created.success)
            assertEquals("Checked out feature/created", created.message)

            git(repository, "switch", "develop")
            Files.writeString(repository.resolve("uncommitted.txt"), "keep me")
            val local = service.createOrCheckoutBranch(listOf(repository.toString()), "feature/created").single()
            assertTrue(local.success)

            git(repository, "switch", "develop")
            git(repository, "branch", "feature/remote")
            git(repository, "push", "origin", "feature/remote")
            git(repository, "branch", "--delete", "feature/remote")
            val remote = service.createOrCheckoutBranch(listOf(repository.toString()), "feature/remote").single()
            assertTrue(remote.success)
            assertEquals("feature/remote", git(repository, "branch", "--show-current").trim())
        } finally {
            testRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `reports fetch failures and rejects unsafe branch names`() {
        withRepository("mph-git-failure-") { repository ->
            commit(repository, "base.txt", "base", "Initial commit")
            val service = GitWorkspaceService()

            val result = service.createOrCheckoutBranch(listOf(repository.toString()), "feature/safe").single()

            assertFalse(result.success)
            assertTrue(result.message.startsWith("Could not fetch origin:"))
            assertThrows(IllegalArgumentException::class.java) {
                service.createOrCheckoutBranch(listOf(repository.toString()), "../main")
            }
            assertThrows(IllegalArgumentException::class.java) {
                service.createOrCheckoutBranch(listOf(repository.toString()), " ")
            }
        }
    }

    private fun withRepository(prefix: String, test: (Path) -> Unit) {
        val repository = Files.createTempDirectory(prefix)
        try {
            configureRepository(repository)
            test(repository)
        } finally {
            repository.toFile().deleteRecursively()
        }
    }

    private fun configureRepository(repository: Path) {
        git(repository, "init", "--initial-branch=develop")
        git(repository, "config", "user.name", "Test User")
        git(repository, "config", "user.email", "test.user@example.org")
        git(repository, "config", "commit.gpgSign", "false")
    }

    private fun commit(repository: Path, fileName: String, content: String, message: String) {
        Files.writeString(repository.resolve(fileName), content)
        git(repository, "add", fileName)
        git(repository, "commit", "-m", message)
    }

    private fun project(pom: Path, repository: Path, gitRootPath: String? = repository.toString()) = MavenProjectInfo(
        groupId = "org.example",
        artifactId = "sample-service",
        version = null,
        pomPath = pom.toString(),
        gitRootPath = gitRootPath,
    )

    private fun pom(version: String) = """
        <project>
            <modelVersion>4.0.0</modelVersion>
            <groupId>org.example</groupId>
            <artifactId>sample-service</artifactId>
            <version>$version</version>
        </project>
    """.trimIndent()

    private fun git(directory: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        check(exitCode == 0) { "git ${arguments.joinToString(" ")} failed ($exitCode): $output" }
        return output
    }
}
