package nl.hicts.mph.intellij.services

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class GitWorkspaceFingerprintTest {
    @Test fun `alignment protects dependent POMs outside Git from changes during sync`() {
        val root = Files.createTempDirectory("mph-dependent-baseline-")
        try {
            val pom = Files.writeString(root.resolve("pom.xml"), "<project><version>1</version></project>")
            val dependent = nl.hicts.mph.intellij.model.MavenProjectInfo("org.example", "dependent", "1", pom.toString(), null)
            val plan = GitRebasePlan("FEATURE-", emptyList(), emptyList(), listOf(dependent))
            val baseline = AlignmentWorkspaceBaseline.capture(plan)
            baseline.verify()
            Files.writeString(pom, "<project><version>2</version></project>")
            assertThrows(IllegalStateException::class.java) { baseline.verify() }
        } finally { GitTestSupport.deleteRepository(root) }
    }
    @Test fun `untracked file bytes and hidden index flags invalidate the baseline`() {
        withRepository { root ->
            val notes = root.resolve("notes.txt")
            Files.writeString(notes, "first draft")
            val baseline = GitWorkspaceFingerprint.capture(root)
            Files.writeString(notes, "other draft")
            assertThrows(IllegalStateException::class.java) { baseline.verify() }
            val beforeFlag = GitWorkspaceFingerprint.capture(root)
            git(root, "update-index", "--assume-unchanged", "pom.xml")
            assertThrows(IllegalStateException::class.java) { beforeFlag.verify() }
            val hidden = GitWorkspaceFingerprint.capture(root)
            Files.writeString(root.resolve("pom.xml"), "<project>hidden change</project>")
            assertThrows(IllegalStateException::class.java) { hidden.verify() }
        }
    }
    @Test fun `detects edits to already dirty and untracked POMs and branch changes`() {
        withRepository { root ->
            Files.writeString(root.resolve("pom.xml"), "<project>local</project>")
            val dirty = GitWorkspaceFingerprint.capture(root)
            dirty.verify()
            Files.writeString(root.resolve("pom.xml"), "<project>later</project>")
            assertThrows(IllegalStateException::class.java) { dirty.verify() }
            val module = Files.createDirectories(root.resolve("module")).resolve("pom.xml")
            Files.writeString(module, "<project>untracked</project>")
            val untracked = GitWorkspaceFingerprint.capture(root)
            Files.writeString(module, "<project>changed untracked</project>")
            assertThrows(IllegalStateException::class.java) { untracked.verify() }
            val branch = GitWorkspaceFingerprint.capture(root)
            git(root, "switch", "-c", "feature/other")
            assertThrows(IllegalStateException::class.java) { branch.verify() }
        }
    }
    @Test fun `staging changes and new Git operations invalidate the baseline`() {
        withRepository { root ->
            Files.writeString(root.resolve("pom.xml"), "<project>local</project>")
            val before = GitWorkspaceFingerprint.capture(root)
            git(root, "add", "pom.xml")
            assertThrows(IllegalStateException::class.java) { before.verify() }
            val staged = GitWorkspaceFingerprint.capture(root)
            Files.createDirectory(root.resolve(".git/rebase-merge"))
            assertThrows(IllegalStateException::class.java) { staged.verify() }
        }
    }
    private fun withRepository(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("mph-fingerprint-")
        try {
            git(root, "init", "--initial-branch=feature/test")
            git(root, "config", "user.name", "Test User")
            git(root, "config", "user.email", "test.user@example.org")
            git(root, "config", "commit.gpgSign", "false")
            Files.writeString(root.resolve("pom.xml"), "<project/>")
            git(root, "add", "pom.xml")
            git(root, "commit", "-m", "Test baseline")
            action(root)
        } finally { GitTestSupport.deleteRepository(root) }
    }
    private fun git(root: Path, vararg args: String): String = GitTestSupport.git(root, *args)
}
