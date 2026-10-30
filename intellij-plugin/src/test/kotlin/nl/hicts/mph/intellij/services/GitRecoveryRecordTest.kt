package nl.hicts.mph.intellij.services

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

class GitRecoveryRecordTest {
    @Test fun `recovery text quotes each shell and contains no executable placeholder`() {
        val root = Files.createTempDirectory("mph-recovery-quoting-")
        try {
            val record = GitRecoveryRecord(root.resolve("a'b").toString(), "feature/test",
                "refs/mph/recovery/fixture/head", "fixture marker", "a".repeat(40), "refs/mph/recovery/fixture/stash")
            assertTrue(record.commands(true).contains("a''b"))
            assertTrue(record.commands(false).contains("a'\"'\"'b"))
            assertTrue(record.commands(true).contains("if (\$LASTEXITCODE -eq 0)"))
            assertTrue(record.commands(false).contains(" &&\n"))
            assertFalse(record.copy(stashId = null).instructions().contains("<stash-object>"))
            assertTrue(record.copy(stashId = null).commands(true).contains("# No stash object"))
        } finally { GitTestSupport.deleteRepository(root) }
    }
    @Test(timeout = 30000) fun `recovery commands run from any directory with quoted paths and never apply after failed creation`() {
        val temporary = Files.createTempDirectory("mph-recovery-commands-")
        val root = Files.createDirectory(temporary.resolve("workspace ' quoted & local"))
        try {
            fun git(vararg args: String) = GitTestSupport.git(root, *args).trim()
            git("init", "--initial-branch=feature/test")
            git("config", "user.name", "Test User")
            git("config", "user.email", "test.user@example.org")
            git("config", "commit.gpgSign", "false")
            Files.writeString(root.resolve("work.txt"), "baseline")
            git("add", ".")
            git("commit", "-m", "Fixture baseline")
            Files.writeString(root.resolve("work.txt"), "staged")
            git("add", ".")
            Files.writeString(root.resolve("work.txt"), "unstaged")
            git("stash", "push", "-m", "fixture stash")
            val stash = git("rev-parse", "refs/stash")
            val id = UUID.randomUUID().toString()
            val ref = "refs/mph/recovery/$id/head"
            git("update-ref", ref, "HEAD")
            val record = GitRecoveryRecord(root.toString(), "feature/test", ref, "fixture stash", stash, "refs/mph/recovery/$id/stash")
            val windows = System.getProperty("os.name").startsWith("Windows")
            val script = temporary.resolve(if (windows) "recover.ps1" else "recover.sh")
            val commands = record.commands(windows).substringAfter('\n')
            assertFalse(commands.contains("<new-directory>"))
            Files.writeString(script, commands + if (windows) "\nexit \$LASTEXITCODE\n" else "\n")
            val output = temporary.resolve("output.txt")
            fun run(): Int {
                val command = if (windows) listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-File", script.toString()) else listOf("sh", script.toString())
                val builder = ProcessBuilder(command).directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(output.toFile())
                builder.environment()["GIT_CONFIG_GLOBAL"] = if (windows) "NUL" else "/dev/null"
                builder.environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                val process = builder.start()
                process.outputStream.close()
                try {
                    check(process.waitFor(20, TimeUnit.SECONDS)) { "Recovery fixture timed out" }
                    return process.exitValue()
                } finally {
                    if (process.isAlive) {
                        process.toHandle().descendants().use { children -> children.forEach { it.destroyForcibly() } }
                        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
                    }
                    process.inputStream.close(); process.errorStream.close()
                }
            }
            val exitCode = run()
            assertEquals(Files.readString(output), 0, exitCode)
            val recovered = temporary.resolve("mph-recovered-$id")
            assertEquals("unstaged", Files.readString(recovered.resolve("work.txt")))
            assertEquals("staged", GitTestSupport.git(recovered, "show", ":work.txt"))
            Files.writeString(recovered.resolve("work.txt"), "later recovered edit")
            assertNotEquals(0, run())
            assertEquals("later recovered edit", Files.readString(recovered.resolve("work.txt")))
        } finally { GitTestSupport.deleteRepository(temporary) }
    }
}
