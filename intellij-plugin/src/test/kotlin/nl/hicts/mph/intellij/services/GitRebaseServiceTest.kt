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
    @Test fun `Windows case aliases cannot bypass protected branch checks`() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        withRepository { root, _ ->
            git(root, "branch", "main")
            git(root, "symbolic-ref", "HEAD", "refs/heads/MAIN")
            assertEquals("MAIN", git(root, "branch", "--show-current").trim())
            val head = git(root, "rev-parse", "HEAD")
            val result = synchronize(root)
            assertEquals(result.message, GitRebaseStatus.SKIPPED, result.status)
            assertTrue(result.message.contains("protected"))
            assertEquals(head, git(root, "rev-parse", "main"))
        }
    }

    @Test fun `Windows case aliases cannot move develop checked out in another worktree`() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        withRepository { root, temporary ->
            advanceDevelop(root)
            git(root, "update-ref", "refs/heads/develop", "HEAD")
            val linked = temporary.resolve("linked-develop")
            git(root, "worktree", "add", linked.toString(), "develop")
            git(linked, "symbolic-ref", "HEAD", "refs/heads/DEVELOP")
            val head = git(root, "rev-parse", "develop")
            val result = synchronize(root)
            assertEquals(result.message, GitRebaseStatus.SKIPPED, result.status)
            assertTrue(result.message.contains("another worktree"))
            assertEquals(head, git(root, "rev-parse", "develop"))
            assertEquals(pom("service", "1.0-SNAPSHOT"), Files.readString(linked.resolve("pom.xml")))
        }
    }

    @Test fun `new ignored files at restoration cannot be overwritten by the stash`() = withRepository { root, _ ->
        git(root, "switch", "develop")
        Files.writeString(root.resolve(".gitignore"), "notes.txt\n")
        git(root, "add", ".gitignore")
        git(root, "commit", "-m", "Ignore generated notes")
        publishDevelop(root)
        git(root, "switch", "feature/test")
        Files.writeString(root.resolve("notes.txt"), "original notes")
        val result = synchronize(root, listener = GitRebaseListener { _, _, message ->
            if (message == "Restoring uncommitted work") Files.writeString(root.resolve("notes.txt"), "new ignored notes")
        })
        assertEquals(GitRebaseStatus.CONFLICT, result.status)
        assertEquals("new ignored notes", Files.readString(root.resolve("notes.txt")))
        assertEquals("original notes", git(root, "show", "refs/stash^3:notes.txt"))
        assertTrue(result.stashPreserved)
    }

    @Test fun `stashed executable index mode is restored after synchronization`() = withRepository { root, _ ->
        Files.writeString(root.resolve("run.txt"), "fixture executable content")
        git(root, "add", "run.txt")
        git(root, "commit", "-m", "Add executable fixture")
        advanceDevelop(root)
        git(root, "update-index", "--chmod=+x", "run.txt")
        val index = git(root, "ls-files", "--stage", "run.txt")
        val result = synchronize(root)
        assertEquals(result.message, GitRebaseStatus.SUCCESS, result.status)
        assertEquals(index, git(root, "ls-files", "--stage", "run.txt"))
        assertEquals("fixture executable content", Files.readString(root.resolve("run.txt")))
    }

    @Test fun `symlink targets are restored without touching the destination`() {
        org.junit.Assume.assumeFalse("Windows symlink creation requires machine privileges", System.getProperty("os.name").startsWith("Windows"))
        withRepository { root, temporary ->
            val target = Files.writeString(temporary.resolve("external.txt"), "external work")
            val link = Files.createSymbolicLink(root.resolve("link.txt"), target)
            val result = synchronize(root)
            assertEquals(result.message, GitRebaseStatus.SUCCESS, result.status)
            assertTrue(Files.isSymbolicLink(link))
            assertEquals(target, Files.readSymbolicLink(link))
            assertEquals("external work", Files.readString(target))
        }
    }

    @Test fun `symbolic develop refs cannot overwrite protected local commits`() {
        listOf("refs/remotes/origin/develop", "refs/heads/develop").forEach { ref ->
            withRepository { root, _ ->
                advanceDevelop(root)
                git(root, "switch", "-c", "main")
                Files.writeString(root.resolve("local-only.txt"), "local committed work")
                git(root, "add", "local-only.txt")
                git(root, "commit", "-m", "Keep local main work")
                val main = git(root, "rev-parse", "main")
                git(root, "switch", "feature/test")
                val head = git(root, "rev-parse", "HEAD")
                Files.writeString(root.resolve("notes.txt"), "uncommitted work")
                git(root, "symbolic-ref", ref, "refs/heads/main")
                val result = synchronize(root)
                assertEquals(result.message, GitRebaseStatus.SKIPPED, result.status)
                assertTrue(result.message.contains("symbolic"))
                assertEquals(head, git(root, "rev-parse", "HEAD"))
                assertEquals(main, git(root, "rev-parse", "main"))
                assertEquals("uncommitted work", Files.readString(root.resolve("notes.txt")))
                assertTrue(git(root, "stash", "list").isBlank())
            }
        }
    }

    @Test fun `configured tag pruning cannot fetch or overwrite tags`() = withRepository { root, _ ->
        git(root, "tag", "remote-only")
        git(root, "bundle", "create", git(root, "remote", "get-url", "origin").trim(), "develop", "refs/tags/remote-only")
        git(root, "tag", "-d", "remote-only")
        git(root, "tag", "local-only")
        git(root, "config", "fetch.prune", "true")
        git(root, "config", "fetch.pruneTags", "true")
        git(root, "config", "remote.origin.pruneTags", "true")
        val before = git(root, "show-ref", "--tags")
        val result = synchronize(root)
        assertEquals(result.message, GitRebaseStatus.SUCCESS, result.status)
        assertEquals(before, git(root, "show-ref", "--tags"))
    }

    @Test fun `external edits before continue are not committed by automatic conflict resolution`() = withRepository { root, _ ->
        Files.writeString(root.resolve("pom.xml"), pom("service", "PREFIX-1.0-SNAPSHOT"))
        git(root, "add", "pom.xml")
        git(root, "commit", "-m", "Prefix feature")
        advanceDevelop(root)
        val result = synchronize(root, listener = GitRebaseListener { _, _, message ->
            if (message == "Continuing after resolving version-only POM conflicts") {
                Files.writeString(root.resolve("notes.txt"), "concurrent work")
                git(root, "add", "notes.txt")
            }
        })
        assertEquals(result.message, GitRebaseStatus.FAILED, result.status)
        assertTrue(Files.isDirectory(root.resolve(".git/rebase-merge")))
        assertEquals("concurrent work", git(root, "show", ":notes.txt"))
        assertTrue(result.recoveryHint.orEmpty().contains("refs/mph/recovery/"))
    }

    @Test fun `gitfile checkouts and protected branches are refused without changing local work`() = withRepository { root, testRoot ->
        val worktree = testRoot.resolve("linked")
        git(root, "worktree", "add", "-b", "feature/linked", worktree.toString(), "HEAD")
        Files.writeString(worktree.resolve("notes.txt"), "linked work")
        assertEquals(GitRebaseStatus.SKIPPED, synchronize(worktree).status)
        assertEquals("linked work", Files.readString(worktree.resolve("notes.txt")))
        listOf("main", "master", "develop").forEach { branch ->
            if (branch == "develop") git(root, "switch", branch) else git(root, "switch", "-c", branch)
            assertEquals(GitRebaseStatus.SKIPPED, synchronize(root).status)
        }
        git(root, "switch", "--detach")
        assertEquals(GitRebaseStatus.SKIPPED, synchronize(root).status)
    }

    @Test fun `empty and already applied commits remain reachable after synchronization`() = withRepository { root, _ ->
        git(root, "commit", "--allow-empty", "-m", "Intentional empty feature commit")
        Files.writeString(root.resolve("feature.txt"), "shared change")
        git(root, "add", "feature.txt")
        git(root, "commit", "-m", "Shared feature change")
        val featureCommit = git(root, "rev-parse", "HEAD").trim()
        git(root, "switch", "develop")
        git(root, "cherry-pick", featureCommit)
        publishDevelop(root)
        git(root, "switch", "feature/test")
        val result = synchronize(root)
        assertEquals(result.message, GitRebaseStatus.SUCCESS, result.status)
        assertTrue(git(root, "log", "--format=%s").contains("Intentional empty feature commit"))
        assertEquals("shared change", Files.readString(root.resolve("feature.txt")))
    }

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
        val origin = testRoot.resolve("origin.bundle")
        val repository = Files.createDirectory(testRoot.resolve("workspace"))
        try {
            git(repository, "init", "--initial-branch=develop")
            git(repository, "config", "user.name", "Test User")
            git(repository, "config", "user.email", "test.user@example.org")
            git(repository, "config", "commit.gpgSign", "false")
            val pom = Files.writeString(repository.resolve("pom.xml"), pom("service", "1.0-SNAPSHOT"))
            git(repository, "add", "pom.xml")
            git(repository, "commit", "-m", "Initial project")
            git(repository, "remote", "add", "origin", origin.toString())
            publishDevelop(repository)

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
            publishDevelop(repository)
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
            assertEquals(originalHead, git(repository, "for-each-ref", "--format=%(objectname)", "refs/mph/recovery/*/head").trim())
            assertTrue(results.single().recoveryHint.orEmpty().contains("recovery.txt"))
            git(repository, "merge-base", "--is-ancestor", "origin/develop", "HEAD")
        } finally {
            GitTestSupport.deleteRepository(testRoot)
        }
    }

    @Test
    fun `preserves all uncommitted work when a rebase requires manual conflict resolution`() {
        val testRoot = Files.createTempDirectory("mph-rebase-conflict-")
        val origin = testRoot.resolve("origin.bundle")
        val repository = Files.createDirectory(testRoot.resolve("workspace"))
        try {
            git(repository, "init", "--initial-branch=develop")
            git(repository, "config", "user.name", "Test User")
            git(repository, "config", "user.email", "test.user@example.org")
            git(repository, "config", "commit.gpgSign", "false")
            val pom = Files.writeString(repository.resolve("pom.xml"), pom("service", "1.0-SNAPSHOT"))
            val source = Files.writeString(repository.resolve("application.txt"), "value=initial\n")
            git(repository, "add", "pom.xml", "application.txt")
            git(repository, "commit", "-m", "Initial project")
            git(repository, "remote", "add", "origin", origin.toString())
            publishDevelop(repository)

            git(repository, "switch", "--create", "feature/upgrade")
            Files.writeString(source, "value=feature\n")
            git(repository, "add", "application.txt")
            git(repository, "commit", "-m", "Change feature value")
            git(repository, "switch", "develop")
            Files.writeString(source, "value=develop\n")
            git(repository, "add", "application.txt")
            git(repository, "commit", "-m", "Change development value")
            publishDevelop(repository)
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
            GitTestSupport.deleteRepository(testRoot)
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
        publishDevelop(root)
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
                publishDevelop(root)
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
        assertTrue(result.message, result.message.contains("Test hook rejected rebase"))
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
        publishDevelop(root)
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
        git(root, "bundle", "create", git(root, "remote", "get-url", "origin").trim(), "HEAD")
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
            if (arguments.take(3) == listOf("update-ref", "--no-deref", "refs/heads/develop")) GitCommandResult(1, "", "Test ref race")
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

    @Test
    fun `safety stash survives clearing ordinary stashes and immediate garbage collection`() = withRepository { root, testRoot ->
        Files.writeString(root.resolve("pom.xml"), pom("service", "STAGED-1.0-SNAPSHOT"))
        git(root, "add", "pom.xml")
        Files.writeString(root.resolve("pom.xml"), pom("service", "UNSTAGED-1.0-SNAPSHOT"))
        Files.writeString(root.resolve("notes.txt"), "untracked work")
        val result = synchronize(root)
        assertEquals(result.message, GitRebaseStatus.SUCCESS, result.status)
        val stashRef = git(root, "for-each-ref", "--format=%(refname)", "refs/mph/recovery/*/stash").trim()
        val headRef = git(root, "for-each-ref", "--format=%(refname)", "refs/mph/recovery/*/head").trim()
        assertTrue(result.recoveryHint.orEmpty().contains(stashRef))
        git(root, "stash", "clear")
        git(root, "reflog", "expire", "--expire=now", "--all")
        git(root, "gc", "--prune=now")
        val recovered = testRoot.resolve("recovered")
        git(root, "worktree", "add", "--detach", recovered.toString(), headRef)
        git(recovered, "stash", "apply", "--index", stashRef)
        assertEquals(pom("service", "STAGED-1.0-SNAPSHOT"), git(recovered, "show", ":pom.xml"))
        assertEquals(pom("service", "UNSTAGED-1.0-SNAPSHOT"), Files.readString(recovered.resolve("pom.xml")))
        assertEquals("untracked work", Files.readString(recovered.resolve("notes.txt")))
    }

    @Test
    fun `failed recovery record update still reports and pins the exact stash`() = withRepository { root, _ ->
        Files.writeString(root.resolve("notes.txt"), "local notes")
        var writes = 0
        lateinit var recoveryFile: Path
        val workflow = GitRebaseWorkflow(recoveryWriter = { path, text ->
            recoveryFile = path
            if (++writes > 1) error("Test disk full after stash")
            writeGitRecoveryRecord(path, text)
        })
        val result = runWorkflow(root, workflow)
        val stash = git(root, "rev-parse", "refs/stash").trim()
        assertEquals(GitRebaseStatus.FAILED, result.status)
        assertTrue(result.stashPreserved)
        assertTrue(result.recoveryHint.orEmpty().contains(stash))
        assertTrue(Files.readString(recoveryFile).contains("Stash marker:"))
        assertEquals(stash, git(root, "for-each-ref", "--format=%(objectname)", "refs/mph/recovery/*/stash").trim())
        git(root, "stash", "apply", "--index", stash)
        assertEquals("local notes", Files.readString(root.resolve("notes.txt")))
    }

    @Test
    fun `exception reporting stash completion still identifies its recovery object`() = withRepository { root, _ ->
        Files.writeString(root.resolve("notes.txt"), "local notes")
        val native = NativeGitCommandRunner()
        val workflow = GitRebaseWorkflow(GitCommandRunner { directory, arguments, progress, environment ->
            val output = native.execute(directory, arguments, progress, environment)
            if (arguments.take(2) == listOf("stash", "push")) error("Test completion failure")
            output
        })
        val result = runWorkflow(root, workflow)
        assertEquals(GitRebaseStatus.FAILED, result.status)
        assertTrue(result.stashPreserved)
        assertTrue(result.recoveryHint.orEmpty().contains(git(root, "rev-parse", "refs/stash").trim()))
        assertEquals("local notes", git(root, "show", "refs/stash^3:notes.txt"))
    }

    @Test
    fun `branch changes during fetch stop before moving develop or stashing`() = withRepository { root, _ ->
        advanceDevelop(root)
        git(root, "update-ref", "refs/heads/develop", "HEAD")
        val oldDevelop = git(root, "rev-parse", "develop")
        Files.writeString(root.resolve("notes.txt"), "local notes")
        val native = NativeGitCommandRunner()
        val workflow = GitRebaseWorkflow(GitCommandRunner { directory, arguments, progress, environment ->
            val output = native.execute(directory, arguments, progress, environment)
            if (arguments.first() == "fetch") git(root, "switch", "develop")
            output
        })
        val result = runWorkflow(root, workflow)
        assertEquals(GitRebaseStatus.FAILED, result.status)
        assertTrue(result.message.contains("branch or HEAD changed"))
        assertEquals(oldDevelop, git(root, "rev-parse", "develop"))
        assertEquals("local notes", Files.readString(root.resolve("notes.txt")))
        assertEquals("", git(root, "stash", "list"))
    }

    @Test
    fun `new work between stash and rebase is not overwritten`() = withRepository { root, _ ->
        advanceDevelop(root)
        Files.writeString(root.resolve("notes.txt"), "stashed notes")
        val head = git(root, "rev-parse", "HEAD")
        val result = synchronize(root) { _, _, message ->
            if (message.startsWith("Rebasing ")) Files.writeString(root.resolve("pom.xml"), "new unsaved work on disk")
        }
        assertEquals(GitRebaseStatus.FAILED, result.status)
        assertTrue(result.stashPreserved)
        assertEquals(head, git(root, "rev-parse", "HEAD"))
        assertEquals("new unsaved work on disk", Files.readString(root.resolve("pom.xml")))
        assertEquals("stashed notes", git(root, "show", "refs/stash^3:notes.txt"))
    }

    @Test
    fun `new ignored collisions between preflight and rebase are preserved`() = withRepository { root, _ ->
        git(root, "switch", "develop")
        Files.writeString(root.resolve("private.txt"), "incoming file")
        git(root, "add", "private.txt")
        git(root, "commit", "-m", "Track file on develop")
        publishDevelop(root)
        git(root, "switch", "feature/test")
        Files.writeString(root.resolve(".git/info/exclude"), "private.txt\n")
        val result = synchronize(root) { _, _, message ->
            if (message.startsWith("Rebasing ")) Files.writeString(root.resolve("private.txt"), "new ignored work")
        }
        assertEquals(result.message, GitRebaseStatus.SKIPPED, result.status)
        assertEquals("new ignored work", Files.readString(root.resolve("private.txt")))
    }

    @Test
    fun `stash is not applied to a different checkout after rebase`() = withRepository { root, _ ->
        advanceDevelop(root)
        Files.writeString(root.resolve("notes.txt"), "feature notes")
        val result = synchronize(root) { _, _, message ->
            if (message == "Restoring uncommitted work") git(root, "switch", "develop")
        }
        assertEquals(GitRebaseStatus.FAILED, result.status)
        assertTrue(result.stashPreserved)
        assertEquals("develop", git(root, "branch", "--show-current").trim())
        assertFalse(Files.exists(root.resolve("notes.txt")))
        assertEquals("feature notes", git(root, "show", "refs/stash^3:notes.txt"))
    }

    @Test
    fun `each repository baseline is captured before processing the next repository`() = withRepository { first, _ ->
        withRepository { second, _ ->
            val plan = GitRebasePlan("PREFIX-", listOf(
                GitRepositoryPlan(first.toString(), "first"), GitRepositoryPlan(second.toString(), "second"),
            ), emptyList())
            val results = GitRebaseWorkflow().rebase(plan, notCancelledIndicator(), GitRebaseListener { repo, _, message ->
                if (repo.rootPath == second.toString() && message == "Starting Git preflight") {
                    Files.writeString(first.resolve("pom.xml"), "changed while the second repository runs")
                }
            })
            assertTrue(results.all { it.status == GitRebaseStatus.SUCCESS })
            assertThrows(IllegalStateException::class.java) { requireNotNull(results.first().workspaceFingerprint).verify() }
            requireNotNull(results.last().workspaceFingerprint).verify()
        }
    }

    @Test
    fun `fetch configuration cannot update unrelated local branches`() = withRepository { root, _ ->
        advanceDevelop(root)
        git(root, "branch", "local-only")
        val original = git(root, "rev-parse", "HEAD")
        git(root, "update-ref", "refs/heads/develop", "HEAD")
        git(root, "config", "remote.origin.fetch", "+refs/heads/*:refs/heads/*")
        git(root, "config", "fetch.prune", "true")
        // Stop immediately after fetch: local develop must still be at the old ID.
        val native = NativeGitCommandRunner()
        val workflow = GitRebaseWorkflow(GitCommandRunner { directory, arguments, progress, environment ->
            val output = native.execute(directory, arguments, progress, environment)
            if (arguments.first() == "fetch") {
                assertEquals(original, git(root, "rev-parse", "develop"))
                assertEquals(original, git(root, "rev-parse", "local-only"))
            }
            output
        })
        val result = runWorkflow(root, workflow)
        assertEquals(result.message, GitRebaseStatus.SUCCESS, result.status)
        assertEquals(original, git(root, "rev-parse", "local-only"))
    }

    @Test
    fun `merge-only committed work is not silently dropped by recreating the merge`() = withRepository { root, _ ->
        git(root, "switch", "-c", "side")
        Files.writeString(root.resolve("side.txt"), "side work")
        git(root, "add", ".")
        git(root, "commit", "-m", "Side work")
        git(root, "switch", "feature/test")
        Files.writeString(root.resolve("feature.txt"), "feature work")
        git(root, "add", ".")
        git(root, "commit", "-m", "Feature work")
        git(root, "merge", "--no-ff", "--no-commit", "side")
        Files.writeString(root.resolve("merge-only.txt"), "work added when merging")
        git(root, "add", ".")
        git(root, "commit", "-m", "Merge with additional work")
        val head = git(root, "rev-parse", "HEAD")
        advanceDevelop(root)
        Files.writeString(root.resolve("notes.txt"), "uncommitted notes")
        val result = synchronize(root)
        assertEquals(result.message, GitRebaseStatus.SKIPPED, result.status)
        assertTrue(result.message.contains("manual merge changes"))
        assertEquals(head, git(root, "rev-parse", "HEAD"))
        assertEquals("work added when merging", Files.readString(root.resolve("merge-only.txt")))
        assertEquals("uncommitted notes", Files.readString(root.resolve("notes.txt")))
        assertEquals("", git(root, "stash", "list"))
    }

    @Test
    fun `version conflicts in paths containing Git wildcard characters are staged literally`() = withRepository { root, _ ->
        git(root, "switch", "develop")
        val module = Files.createDirectory(root.resolve("[module]"))
        val modulePom = module.resolve("pom.xml")
        Files.writeString(modulePom, pom("module", "1.0-SNAPSHOT"))
        git(root, "add", ".")
        git(root, "commit", "-m", "Add module")
        git(root, "switch", "feature/test")
        git(root, "merge", "--ff-only", "develop")
        Files.writeString(modulePom, pom("module", "PREFIX-1.0-SNAPSHOT"))
        git(root, "add", ".")
        git(root, "commit", "-m", "Prefix module")
        git(root, "switch", "develop")
        Files.writeString(modulePom, pom("module", "2.0-SNAPSHOT"))
        git(root, "add", ".")
        git(root, "commit", "-m", "Advance module")
        publishDevelop(root)
        git(root, "switch", "feature/test")
        val result = synchronize(root)
        assertEquals(result.message, GitRebaseStatus.SUCCESS, result.status)
        assertEquals(pom("module", "2.0-SNAPSHOT"), Files.readString(modulePom))
    }

    @Test
    fun `a leftover REBASE_HEAD does not prevent synchronization or stash restoration`() = withRepository { root, _ ->
        git(root, "update-ref", "REBASE_HEAD", "HEAD")
        Files.writeString(root.resolve("notes.txt"), "local notes")
        val result = synchronize(root)
        assertEquals(result.message, GitRebaseStatus.SUCCESS, result.status)
        assertEquals("local notes", Files.readString(root.resolve("notes.txt")))
        requireNotNull(result.workspaceFingerprint).verify()
    }

    private fun runWorkflow(root: Path, workflow: GitRebaseWorkflow): GitRepositoryResult = workflow.rebase(
        GitRebasePlan("PREFIX-", listOf(GitRepositoryPlan(root.toString(), "service")), emptyList()),
        notCancelledIndicator(), GitRebaseListener { _, _, _ -> },
    ).single()

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
        publishDevelop(root)
        git(root, "switch", "feature/test")
    }

    // A local bundle exposes real remote refs to fetch without network or a helper shell.
    private fun publishDevelop(root: Path) {
        val origin = git(root, "remote", "get-url", "origin").trim()
        git(root, "bundle", "create", origin, "develop")
        git(root, "fetch", "origin", "+refs/heads/develop:refs/remotes/origin/develop")
    }

    private fun withRepository(action: (Path, Path) -> Unit) {
        val testRoot = Files.createTempDirectory("mph-rebase-safety-")
        val root = Files.createDirectory(testRoot.resolve("workspace"))
        try {
            val origin = testRoot.resolve("origin.bundle")
            git(root, "init", "--initial-branch=develop")
            git(root, "config", "user.name", "Test User")
            git(root, "config", "user.email", "test.user@example.org")
            git(root, "config", "commit.gpgSign", "false")
            Files.writeString(root.resolve("pom.xml"), pom("service", "1.0-SNAPSHOT"))
            git(root, "add", "pom.xml")
            git(root, "commit", "-m", "Initial project")
            git(root, "remote", "add", "origin", origin.toString())
            publishDevelop(root)
            git(root, "switch", "-c", "feature/test")
            action(root, testRoot)
        } finally {
            GitTestSupport.deleteRepository(testRoot)
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

    private fun git(directory: Path, vararg arguments: String): String = GitTestSupport.git(directory, *arguments)

    private fun gitExitCode(directory: Path, vararg arguments: String): Int = GitTestSupport.run(directory, *arguments).exitCode

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
