package nl.hicts.mph.intellij.services

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class GitWorkspaceFingerprintTest {
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
        } finally { root.toFile().deleteRecursively() }
    }
    private fun git(root: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(root.toFile()).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { text }
    }
}
