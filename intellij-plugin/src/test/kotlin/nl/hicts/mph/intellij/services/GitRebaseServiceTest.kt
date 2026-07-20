package nl.hicts.mph.intellij.services

import com.intellij.openapi.progress.ProgressIndicator
import nl.hicts.mph.intellij.model.MavenProjectInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.Proxy

class GitRebaseServiceTest {
    @Test
    fun `detects the part before the semantic version as prefix`() {
        assertEquals("PREFIX-1234-", GitVersionPrefix.detect("PREFIX-1234-4.1-SNAPSHOT"))
        assertEquals("feature-", GitVersionPrefix.detect("feature-1.2.3"))
        assertEquals(null, GitVersionPrefix.detect("4.1-SNAPSHOT"))
        assertEquals(null, GitVersionPrefix.detect("not-a-version"))
    }

    @Test
    fun `creates one repository plan and includes all modules for alignment`() {
        val repository = Files.createTempDirectory("mph-rebase-plan-")
        val rootPom = Files.writeString(repository.resolve("pom.xml"), pom("parent", "PREFIX-1234-1.0-SNAPSHOT"))
        val moduleDirectory = Files.createDirectories(repository.resolve("module"))
        val modulePom = Files.writeString(
            moduleDirectory.resolve("pom.xml"),
            pom("module", "PREFIX-1234-1.1-SNAPSHOT"),
        )
        val root = project("parent", rootPom.toString(), repository.toString())
        val module = project("module", modulePom.toString(), repository.toString())

        try {
            val plan = GitRebaseService().createPlan(listOf(root), listOf(root, module))

            assertEquals("PREFIX-1234-", plan.prefix)
            assertEquals(1, plan.repositories.size)
            assertEquals(listOf(root, module), plan.alignmentProjects)
        } finally {
            Files.deleteIfExists(modulePom)
            Files.deleteIfExists(moduleDirectory)
            Files.deleteIfExists(rootPom)
            Files.deleteIfExists(repository)
        }
    }

    @Test
    fun `rejects different prefixes within the selected repositories`() {
        val repository = Files.createTempDirectory("mph-rebase-prefix-")
        val firstPom = Files.writeString(repository.resolve("first.xml"), pom("first", "ONE-1.0-SNAPSHOT"))
        val secondPom = Files.writeString(repository.resolve("second.xml"), pom("second", "TWO-1.0-SNAPSHOT"))
        val first = project("first", firstPom.toString(), repository.toString())
        val second = project("second", secondPom.toString(), repository.toString())
        try {
            assertThrows(IllegalArgumentException::class.java) {
                GitRebaseService().createPlan(listOf(first), listOf(first, second))
            }
        } finally {
            Files.deleteIfExists(firstPom)
            Files.deleteIfExists(secondPom)
            Files.deleteIfExists(repository)
        }
    }

    @Test
    fun `resolves only version element conflict hunks using the current side`() {
        val versionConflict = Files.createTempFile("mph-version-conflict-", ".xml")
        val sourceConflict = Files.createTempFile("mph-source-conflict-", ".xml")
        Files.writeString(
            versionConflict,
            """
                <project>
                <<<<<<< HEAD
                  <version>2.0-SNAPSHOT</version>
                =======
                  <version>PREFIX-1234-1.0-SNAPSHOT</version>
                >>>>>>> feature
                </project>
            """.trimIndent(),
        )
        val sourceContent = """
            <<<<<<< HEAD
            fun current() = true
            =======
            fun incoming() = true
            >>>>>>> feature
        """.trimIndent()
        Files.writeString(sourceConflict, sourceContent)
        try {
            assertTrue(GitVersionConflictResolver.resolve(versionConflict))
            assertTrue(Files.readString(versionConflict).contains("<version>2.0-SNAPSHOT</version>"))
            assertFalse(Files.readString(versionConflict).contains("<<<<<<<"))
            assertFalse(GitVersionConflictResolver.resolve(sourceConflict))
            assertEquals(sourceContent, Files.readString(sourceConflict))
        } finally {
            Files.deleteIfExists(versionConflict)
            Files.deleteIfExists(sourceConflict)
        }
    }

    @Test
    fun `rebases a real repository and restores uncommitted work`() {
        val testRoot = Files.createTempDirectory("mph-rebase-integration-")
        val origin = testRoot.resolve("origin.git")
        val repository = Files.createDirectory(testRoot.resolve("workspace"))
        try {
            git(testRoot, "init", "--bare", origin.toString())
            git(repository, "init", "--initial-branch=develop")
            git(repository, "config", "user.name", "Test User")
            git(repository, "config", "user.email", "test.user@example.org")
            git(repository, "config", "commit.gpgSign", "false")
            val pom = Files.writeString(repository.resolve("pom.xml"), pom("service", "1.0-SNAPSHOT"))
            git(repository, "add", "pom.xml")
            git(repository, "commit", "-m", "Initial project")
            git(repository, "remote", "add", "origin", origin.toString())
            git(repository, "push", "--set-upstream", "origin", "develop")

            git(repository, "switch", "--create", "feature/upgrade")
            Files.writeString(repository.resolve("feature.txt"), "feature work\n")
            git(repository, "add", "feature.txt")
            git(repository, "commit", "-m", "Add feature work")
            git(repository, "switch", "develop")
            Files.writeString(pom, pom("service", "2.0-SNAPSHOT"))
            git(repository, "add", "pom.xml")
            git(repository, "commit", "-m", "Upgrade development version")
            git(repository, "push", "origin", "develop")
            git(repository, "switch", "feature/upgrade")

            Files.writeString(pom, pom("service", "PREFIX-1234-1.0-SNAPSHOT"))
            Files.writeString(repository.resolve("notes.txt"), "uncommitted notes\n")
            val project = project("service", pom.toString(), repository.toString())
            val service = GitRebaseService()
            val plan = service.createPlan(listOf(project), listOf(project))

            val results = service.rebase(plan, notCancelledIndicator()) { _, _, _ -> }

            assertEquals(
                "${results.single().message}\n${Files.readString(pom)}",
                GitRebaseStatus.SUCCESS,
                results.single().status,
            )
            assertEquals("feature/upgrade", git(repository, "branch", "--show-current").trim())
            assertEquals("2.0-SNAPSHOT", PomReferenceVersionEditor.findProjectVersion(Files.readString(pom)))
            assertTrue(Files.exists(repository.resolve("notes.txt")))
            assertTrue(git(repository, "stash", "list").isBlank())
            git(repository, "merge-base", "--is-ancestor", "origin/develop", "HEAD")
        } finally {
            testRoot.toFile().deleteRecursively()
        }
    }

    private fun project(artifactId: String, pomPath: String, root: String) = MavenProjectInfo(
        groupId = "org.example",
        artifactId = artifactId,
        version = null,
        pomPath = pomPath,
        gitRootPath = root,
    )

    private fun pom(artifactId: String, version: String) = """
        <project>
            <modelVersion>4.0.0</modelVersion>
            <groupId>org.example</groupId>
            <artifactId>$artifactId</artifactId>
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

    private fun notCancelledIndicator(): ProgressIndicator = Proxy.newProxyInstance(
        ProgressIndicator::class.java.classLoader,
        arrayOf(ProgressIndicator::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Double.TYPE -> 0.0
            java.lang.Integer.TYPE -> 0
            else -> null
        }
    } as ProgressIndicator
}
