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
    fun `rebases a local-only feature branch and restores uncommitted work without disturbing existing stashes`() {
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
            Files.writeString(repository.resolve("earlier-notes.txt"), "keep this existing stash\n")
            git(repository, "stash", "push", "--include-untracked", "--message", "pre-existing test stash")
            git(repository, "switch", "develop")
            Files.writeString(pom, pom("service", "2.0-SNAPSHOT"))
            git(repository, "add", "pom.xml")
            git(repository, "commit", "-m", "Upgrade development version")
            git(repository, "push", "origin", "develop")
            git(repository, "switch", "feature/upgrade")

            Files.writeString(pom, pom("service", "PREFIX-1234-1.0-SNAPSHOT"))
            git(repository, "add", "pom.xml")
            git(repository, "commit", "-m", "Commit version prefix")
            val originalHead = git(repository, "rev-parse", "HEAD").trim()
            Files.writeString(repository.resolve("feature.txt"), "staged feature work\n")
            git(repository, "add", "feature.txt")
            Files.writeString(repository.resolve("feature.txt"), "unstaged feature work\n")
            Files.writeString(repository.resolve("notes.txt"), "uncommitted notes\n")
            assertEquals(
                "The local feature branch must not require a remote counterpart",
                1,
                gitExitCode(
                    repository,
                    "show-ref",
                    "--verify",
                    "--quiet",
                    "refs/remotes/origin/feature/upgrade",
                ),
            )
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
            val remainingStashes = git(repository, "stash", "list")
            assertTrue(remainingStashes.contains("pre-existing test stash"))
            assertTrue(remainingStashes.contains("mph: rebase"))
            assertTrue(results.single().stashPreserved)
            assertEquals("staged feature work\n", git(repository, "show", ":feature.txt"))
            assertEquals("unstaged feature work\n", Files.readString(repository.resolve("feature.txt")))
            assertEquals(originalHead, git(repository, "for-each-ref", "--format=%(objectname)", "refs/mph/recovery").trim())
            assertTrue(results.single().recoveryHint.orEmpty().contains("recovery.txt"))
            git(repository, "merge-base", "--is-ancestor", "origin/develop", "HEAD")
        } finally {
            testRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `preserves all uncommitted work when a rebase requires manual conflict resolution`() {
        val testRoot = Files.createTempDirectory("mph-rebase-conflict-")
        val origin = testRoot.resolve("origin.git")
        val repository = Files.createDirectory(testRoot.resolve("workspace"))
        try {
            git(testRoot, "init", "--bare", origin.toString())
            git(repository, "init", "--initial-branch=develop")
            git(repository, "config", "user.name", "Test User")
            git(repository, "config", "user.email", "test.user@example.org")
            git(repository, "config", "commit.gpgSign", "false")
            val pom = Files.writeString(repository.resolve("pom.xml"), pom("service", "1.0-SNAPSHOT"))
            val source = Files.writeString(repository.resolve("application.txt"), "value=initial\n")
            git(repository, "add", "pom.xml", "application.txt")
            git(repository, "commit", "-m", "Initial project")
            git(repository, "remote", "add", "origin", origin.toString())
            git(repository, "push", "--set-upstream", "origin", "develop")

            git(repository, "switch", "--create", "feature/upgrade")
            Files.writeString(source, "value=feature\n")
            git(repository, "add", "application.txt")
            git(repository, "commit", "-m", "Change feature value")
            git(repository, "switch", "develop")
            Files.writeString(source, "value=develop\n")
            git(repository, "add", "application.txt")
            git(repository, "commit", "-m", "Change development value")
            git(repository, "push", "origin", "develop")
            git(repository, "switch", "feature/upgrade")

            Files.writeString(pom, pom("service", "PREFIX-1234-1.0-SNAPSHOT"))
            val notes = Files.writeString(repository.resolve("notes.txt"), "uncommitted notes\n")
            val project = project("service", pom.toString(), repository.toString())
            val service = GitRebaseService()
            val result = service
                .rebase(
                    service.createPlan(listOf(project), listOf(project)),
                    notCancelledIndicator(),
                ) { _, _, _ -> }
                .single()

            assertEquals(GitRebaseStatus.CONFLICT, result.status)
            assertTrue(result.stashPreserved)
            assertFalse(Files.exists(notes))
            val stashId = git(repository, "stash", "list", "--format=%H").lineSequence().first()
            assertTrue(stashId.isNotBlank())

            git(repository, "rebase", "--abort")
            git(repository, "stash", "apply", stashId)
            assertEquals("PREFIX-1234-1.0-SNAPSHOT", PomReferenceVersionEditor.findProjectVersion(Files.readString(pom)))
            assertEquals("uncommitted notes\n", Files.readString(notes))
        } finally {
            testRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `uncommitted version conflicts preserve the original version and index in the stash`() = withRepository { root, _ ->
        advanceDevelop(root)
        val localPom = pom("service", "LOCAL-7.0-SNAPSHOT")
        Files.writeString(root.resolve("pom.xml"), localPom)
        git(root, "add", "pom.xml")
        val result = synchronize(root)
        assertEquals(GitRebaseStatus.CONFLICT, result.status)
        assertTrue(result.stashPreserved)
        val stash = git(root, "rev-parse", "refs/stash").trim()
        assertEquals(localPom, git(root, "show", "$stash:pom.xml"))
        assertEquals(localPom, git(root, "show", "$stash^2:pom.xml"))
        assertTrue(result.recoveryHint.orEmpty().contains("partial"))
    }

    @Test
    fun `ignored files that develop would overwrite block synchronization`() = withRepository { root, _ ->
        git(root, "switch", "develop")
        Files.writeString(root.resolve("private.txt"), "remote content")
        git(root, "add", "private.txt")
        git(root, "commit", "-m", "Track incoming file")
        git(root, "push", "origin", "develop")
        git(root, "switch", "feature/test")
        Files.writeString(root.resolve(".git/info/exclude"), "private.txt\n")
        Files.writeString(root.resolve("private.txt"), "irreplaceable ignored work")
        val before = git(root, "rev-parse", "HEAD")
        val result = synchronize(root)
        assertEquals(GitRebaseStatus.SKIPPED, result.status)
        assertTrue(result.message.contains("Ignored"))
        assertEquals("irreplaceable ignored work", Files.readString(root.resolve("private.txt")))
        assertEquals(before, git(root, "rev-parse", "HEAD"))
    }

    @Test
    fun `ignored file and directory collisions are both protected`() {
        for (incomingIsDirectory in listOf(false, true)) {
            withRepository { root, _ ->
                git(root, "switch", "develop")
                val obstacle = root.resolve("obstacle")
                val incoming = if (incomingIsDirectory) Files.createDirectory(obstacle).resolve("remote.txt") else obstacle
                Files.writeString(incoming, "remote content")
                git(root, "add", ".")
                git(root, "commit", "-m", "Add incoming path")
                git(root, "push", "origin", "develop")
                git(root, "switch", "feature/test")
                Files.writeString(root.resolve(".git/info/exclude"), "obstacle\n")
                val local = if (incomingIsDirectory) obstacle else Files.createDirectory(obstacle).resolve("local.txt")
                Files.writeString(local, "local work")
                val result = synchronize(root)
                assertEquals(result.message, GitRebaseStatus.SKIPPED, result.status)
                assertEquals("local work", Files.readString(local))
            }
        }
    }

    @Test
    fun `active operations without a REBASE_HEAD are refused`() = withRepository { root, _ ->
        for (state in listOf("rebase-merge", "rebase-apply", "sequencer")) {
            val directory = Files.createDirectory(root.resolve(".git/$state"))
            assertEquals(GitRebaseStatus.SKIPPED, synchronize(root).status)
            Files.delete(directory)
        }
    }

    @Test
    fun `develop checked out in another worktree is not moved`() = withRepository { root, testRoot ->
        git(root, "worktree", "add", testRoot.resolve("other").toString(), "develop")
        val before = git(root, "rev-parse", "develop")
        val result = synchronize(root)
        assertEquals(GitRebaseStatus.SKIPPED, result.status)
        assertTrue(result.message.contains("another worktree"))
        assertEquals(before, git(root, "rev-parse", "develop"))
    }

    @Test
    fun `hidden index changes are refused without stashing`() = withRepository { root, _ ->
        git(root, "update-index", "--assume-unchanged", "pom.xml")
        Files.writeString(root.resolve("pom.xml"), "hidden local content")
        assertEquals(GitRebaseStatus.SKIPPED, synchronize(root).status)
        assertEquals("hidden local content", Files.readString(root.resolve("pom.xml")))
        assertEquals("", git(root, "stash", "list"))
    }

    @Test
    fun `fetch updates develop explicitly even with a narrow remote mapping`() = withRepository { root, _ ->
        advanceDevelop(root)
        git(root, "update-ref", "refs/remotes/origin/develop", "HEAD")
        git(root, "config", "remote.origin.fetch", "+refs/heads/other:refs/remotes/origin/other")
        val result = synchronize(root)
        assertEquals(result.message, GitRebaseStatus.SUCCESS, result.status)
        assertEquals("2.0-SNAPSHOT", PomReferenceVersionEditor.findProjectVersion(Files.readString(root.resolve("pom.xml"))))
    }

    @Test
    fun `stop during stash creation finishes the stash and reports how to recover it`() = withRepository { root, _ ->
        Files.writeString(root.resolve("notes.txt"), "keep my notes")
        val service = GitRebaseService()
        val original = git(root, "rev-parse", "HEAD")
        val indicator = notCancelledIndicator()
        val result = synchronize(root, service, indicator) { _, _, message ->
            if (message == "Stashing tracked and untracked work") indicator.cancel()
        }
        assertEquals(GitRebaseStatus.CANCELLED, result.status)
        assertTrue(result.stashPreserved)
        assertTrue(result.recoveryHint.orEmpty().contains("recovery.txt"))
        assertEquals(original, git(root, "rev-parse", "HEAD"))
        git(root, "stash", "apply", "--index")
        assertEquals("keep my notes", Files.readString(root.resolve("notes.txt")))
    }

    @Test
    fun `unexpected failures after stashing retain recovery details`() = withRepository { root, _ ->
        Files.writeString(root.resolve("notes.txt"), "keep my notes")
        val result = synchronize(root) { _, _, message ->
            if (message.startsWith("Rebasing ")) error("Injected failure")
        }
        assertEquals(GitRebaseStatus.FAILED, result.status)
        assertTrue(result.stashPreserved)
        assertTrue(result.recoveryHint.orEmpty().contains("recovery.txt"))
        assertTrue(git(root, "stash", "list").contains("mph: rebase"))
    }

    @Test
    fun `a rejected rebase hook reports failure and retains stashed work`() = withRepository { root, _ ->
        advanceDevelop(root)
        Files.writeString(root.resolve("notes.txt"), "recoverable work")
        val hook = Files.writeString(root.resolve(".git/hooks/pre-rebase"), "#!/bin/sh\necho 'Test hook rejected rebase' >&2\nexit 1\n")
        hook.toFile().setExecutable(true)
        val result = synchronize(root)
        assertEquals(result.message, GitRebaseStatus.FAILED, result.status)
        assertTrue(result.message.contains("Test hook rejected rebase"))
        assertTrue(result.stashPreserved)
        git(root, "stash", "apply", "--index")
        assertEquals("recoverable work", Files.readString(root.resolve("notes.txt")))
    }

    @Test
    fun `version resolver does not accept structural XML disguised as a version line`() {
        val conflict = """
            <<<<<<< HEAD
            <version>2</version><artifactId>changed</artifactId><version>3</version>
            =======
            <version>1</version>
            >>>>>>> feature
        """.trimIndent()
        assertEquals(null, GitVersionConflictResolver.resolvedContent(conflict))
        val renamedProperty = "<<<<<<< HEAD\n<spring.version>2</spring.version>\n=======\n<other.version>1</other.version>\n>>>>>>> feature\n"
        assertEquals(null, GitVersionConflictResolver.resolvedContent(renamedProperty))
        val addedVersion = "<<<<<<< HEAD\n<version>2</version>\n=======\n<version>1</version>\n<version>3</version>\n>>>>>>> feature\n"
        assertEquals(null, GitVersionConflictResolver.resolvedContent(addedVersion))
        val crlf = "<<<<<<< HEAD\r\n<version>2</version>\r\n=======\r\n<version>1</version>\r\n>>>>>>> feature\r\n"
        assertEquals("<version>2</version>\r\n", GitVersionConflictResolver.resolvedContent(crlf))
    }

    @Test
    fun `mixed conflict sets leave even version-only files untouched`() = withRepository { root, _ ->
        Files.writeString(root.resolve("pom.xml"), pom("service", "PREFIX-1.0-SNAPSHOT"))
        Files.writeString(root.resolve("source.txt"), "base")
        git(root, "add", ".")
        git(root, "commit", "-m", "Feature version and source")
        git(root, "switch", "develop")
        Files.writeString(root.resolve("pom.xml"), pom("service", "2.0-SNAPSHOT"))
        Files.writeString(root.resolve("source.txt"), "develop")
        git(root, "add", ".")
        git(root, "commit", "-m", "Develop version and source")
        git(root, "push", "origin", "develop")
        git(root, "switch", "feature/test")
        val result = synchronize(root)
        assertEquals(GitRebaseStatus.CONFLICT, result.status)
        assertTrue(Files.readString(root.resolve("pom.xml")).contains("<<<<<<<"))
        assertTrue(git(root, "diff", "--name-only", "--diff-filter=U").contains("pom.xml"))
    }

    @Test
    fun `merge history is preserved and configured updateRefs cannot rewrite other branches`() = withRepository { root, _ ->
        git(root, "switch", "-c", "side")
        Files.writeString(root.resolve("side.txt"), "side work")
        git(root, "add", ".")
        git(root, "commit", "-m", "Side change")
        git(root, "switch", "feature/test")
        Files.writeString(root.resolve("feature.txt"), "feature work")
        git(root, "add", ".")
        git(root, "commit", "-m", "Feature change")
        git(root, "merge", "--no-ff", "side", "-m", "Merge side")
        git(root, "branch", "do-not-move")
        val originalHead = git(root, "rev-parse", "HEAD")
        git(root, "config", "rebase.updateRefs", "true")
        advanceDevelop(root)
        val result = synchronize(root)
        assertEquals(result.message, GitRebaseStatus.SUCCESS, result.status)
        assertEquals(originalHead, git(root, "rev-parse", "do-not-move"))
        assertEquals(1, git(root, "rev-list", "--merges", "origin/develop..HEAD").lineSequence().filter(String::isNotBlank).count())
        assertEquals("side work", Files.readString(root.resolve("side.txt")))
        assertEquals("feature work", Files.readString(root.resolve("feature.txt")))
    }

    @Test
    fun `missing remote develop cannot silently reuse a stale remote tracking ref`() = withRepository { root, _ ->
        val original = git(root, "rev-parse", "HEAD")
        git(root, "push", "origin", "--delete", "develop")
        Files.writeString(root.resolve("notes.txt"), "untouched")
        val result = synchronize(root)
        assertEquals(GitRebaseStatus.FAILED, result.status)
        assertEquals(original, git(root, "rev-parse", "HEAD"))
        assertEquals("untouched", Files.readString(root.resolve("notes.txt")))
        assertEquals("", git(root, "stash", "list"))
    }

    @Test
    fun `a second synchronization cannot reset cancellation or touch the same repository`() = withRepository { root, _ ->
        val service = GitRebaseService()
        var checked = false
        val result = synchronize(root, service) { _, _, message ->
            if (message == "Starting Git preflight") {
                assertThrows(IllegalStateException::class.java) { synchronize(root, service) }
                checked = true
            }
        }
        assertTrue(checked)
        assertEquals(result.message, GitRebaseStatus.SUCCESS, result.status)
    }

    @Test
    fun `stop during restoration reports cancellation while retaining restored work and backup`() = withRepository { root, _ ->
        Files.writeString(root.resolve("notes.txt"), "preserved")
        val service = GitRebaseService()
        val indicator = notCancelledIndicator()
        val result = synchronize(root, service, indicator) { _, _, message ->
            if (message == "Restoring uncommitted work") indicator.cancel()
        }
        assertEquals(GitRebaseStatus.CANCELLED, result.status)
        assertTrue(result.stashPreserved)
        assertEquals("preserved", Files.readString(root.resolve("notes.txt")))
    }

    @Test
    fun `injected ref race stops before stashing local work`() = withRepository { root, _ ->
        advanceDevelop(root)
        Files.writeString(root.resolve("notes.txt"), "local notes")
        val native = NativeGitCommandRunner()
        val workflow = GitRebaseWorkflow(GitCommandRunner { directory, arguments, progress, environment ->
            if (arguments.take(2) == listOf("update-ref", "refs/heads/develop")) GitCommandResult(1, "", "Test ref race")
            else native.execute(directory, arguments, progress, environment)
        })
        val result = workflow.rebase(GitRebasePlan("PREFIX-", listOf(GitRepositoryPlan(root.toString(), "service")), emptyList()), notCancelledIndicator(), GitRebaseListener { _, _, _ -> }).single()
        assertEquals(GitRebaseStatus.FAILED, result.status)
        assertTrue(result.message.contains("Test ref race"))
        assertEquals("local notes", Files.readString(root.resolve("notes.txt")))
        assertEquals("", git(root, "stash", "list"))
    }

    @Test
    fun `recovery write failure leaves local files untouched`() = withRepository { root, _ ->
        Files.writeString(root.resolve("notes.txt"), "local notes")
        val workflow = GitRebaseWorkflow(recoveryWriter = { _, _ -> error("Test disk full") })
        val result = workflow.rebase(GitRebasePlan("PREFIX-", listOf(GitRepositoryPlan(root.toString(), "service")), emptyList()), notCancelledIndicator(), GitRebaseListener { _, _, _ -> }).single()
        assertEquals(GitRebaseStatus.FAILED, result.status)
        assertTrue(result.message.contains("Test disk full"))
        assertEquals("local notes", Files.readString(root.resolve("notes.txt")))
        assertEquals("", git(root, "stash", "list"))
    }

    private fun synchronize(
        root: Path,
        service: GitRebaseService = GitRebaseService(),
        indicator: ProgressIndicator = notCancelledIndicator(),
        listener: GitRebaseListener = GitRebaseListener { _, _, _ -> },
    ): GitRepositoryResult = service.rebase(
        GitRebasePlan("PREFIX-", listOf(GitRepositoryPlan(root.toString(), "service")), emptyList()),
        indicator, listener,
    ).single()

    private fun advanceDevelop(root: Path) {
        git(root, "switch", "develop")
        Files.writeString(root.resolve("pom.xml"), pom("service", "2.0-SNAPSHOT"))
        git(root, "add", "pom.xml")
        git(root, "commit", "-m", "Advance develop")
        git(root, "push", "origin", "develop")
        git(root, "switch", "feature/test")
    }

    private fun withRepository(action: (Path, Path) -> Unit) {
        val testRoot = Files.createTempDirectory("mph-rebase-safety-")
        val root = Files.createDirectory(testRoot.resolve("workspace"))
        try {
            val origin = testRoot.resolve("origin.git")
            git(testRoot, "init", "--bare", origin.toString())
            git(root, "init", "--initial-branch=develop")
            git(root, "config", "user.name", "Test User")
            git(root, "config", "user.email", "test.user@example.org")
            git(root, "config", "commit.gpgSign", "false")
            Files.writeString(root.resolve("pom.xml"), pom("service", "1.0-SNAPSHOT"))
            git(root, "add", "pom.xml")
            git(root, "commit", "-m", "Initial project")
            git(root, "remote", "add", "origin", origin.toString())
            git(root, "push", "origin", "develop")
            git(root, "switch", "-c", "feature/test")
            action(root, testRoot)
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

    private fun gitExitCode(directory: Path, vararg arguments: String): Int = ProcessBuilder(listOf("git") + arguments)
        .directory(directory.toFile())
        .redirectErrorStream(true)
        .start()
        .apply { inputStream.bufferedReader().use { it.readText() } }
        .waitFor()

    private fun notCancelledIndicator(): ProgressIndicator {
        var cancelled = false
        return Proxy.newProxyInstance(
        ProgressIndicator::class.java.classLoader,
        arrayOf(ProgressIndicator::class.java),
    ) { _, method, _ ->
        when {
            method.name == "cancel" -> { cancelled = true; null }
            method.name == "isCanceled" -> cancelled
            else -> when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Double.TYPE -> 0.0
            java.lang.Integer.TYPE -> 0
            else -> null
        }
            }
        } as ProgressIndicator
    }
}
