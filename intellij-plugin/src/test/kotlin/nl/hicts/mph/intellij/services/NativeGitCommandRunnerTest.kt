package nl.hicts.mph.intellij.services

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class NativeGitCommandRunnerTest {
    private val root = Path.of(System.getProperty("java.io.tmpdir"))
    private fun runner(mode: String, timeout: Long = 5000) = NativeGitCommandRunner(
        listOf(javaExecutable(), "-cp", fakeClasspath(), FakeGitProcess::class.java.name, mode), timeout, 100,
    )

    @Test(timeout = 30000) fun `closes stdin and captures both full pipes`() {
        assertEquals("stdin closed", runner("stdin").execute(root, listOf("rev-parse"), null, emptyMap()).output)
        val result = runner("output", 15000).execute(root, listOf("rev-parse"), null, emptyMap())
        assertEquals(result.stderr.takeLast(200), 0, result.exitCode)
        assertEquals("o".repeat(256 * 8192), result.output)
        assertEquals("e".repeat(256 * 8192), result.stderr)
    }

    @Test(timeout = 15000) fun `times out reads but waits for writes and ignores failed listeners`() {
        val read = runner("wait", 150).execute(root, listOf("rev-parse"), null, emptyMap())
        assertEquals(-1, read.exitCode)
        assertTrue(read.diagnostic.contains("timed out"))
        var notifications = 0
        val write = runner("wait", 150).execute(root, listOf("rebase"), { notifications++; error("Disposed test listener") }, emptyMap())
        assertEquals(0, write.exitCode)
        assertEquals("finished", write.output)
        assertTrue(notifications > 1)
    }

    @Test(timeout = 15000) fun `does not wait for inherited output handles after Git exits`() {
        val started = System.nanoTime()
        val result = runner("descendant").execute(root, listOf("rev-parse"), null, emptyMap())
        val child = ProcessHandle.of(result.output.trim().toLong()).orElse(null)
        try {
            assertEquals(result.diagnostic, 0, result.exitCode)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 4000)
        } finally {
            child?.destroyForcibly()
            child?.onExit()?.get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun `repository environment cannot redirect Git and diagnostics redact credentials`() {
        val result = runner("environment").execute(root, listOf("rev-parse"), null,
            mapOf("GIT_DIR" to "other", "GIT_INDEX_FILE" to "other", "GIT_WORK_TREE" to "other"))
        assertEquals(result.diagnostic, 0, result.exitCode)
        assertEquals("null:null:null:0", result.output)
        val diagnostic = GitCommandResult(1, "https://fixture-user:fixture-password@fragment@example.org/repo?private_token=fixture-token&custom=fixture-query",
            "Authorization: Bearer fixture-authorization").diagnostic
        listOf("fixture-user", "fixture-password", "fragment", "fixture-token", "fixture-query", "fixture-authorization").forEach {
            assertFalse(diagnostic.contains(it))
        }
        assertTrue(diagnostic.contains("example.org/repo"))
    }
}

private fun javaExecutable() = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString()
private fun fakeClasspath() = listOf(FakeGitProcess::class.java, Unit::class.java)
    .joinToString(java.io.File.pathSeparator) { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }

internal object FakeGitProcess {
    @JvmStatic fun main(args: Array<String>) {
        when (args.first()) {
            "stdin" -> { while (System.`in`.read() != -1) { /* drain */ }; print("stdin closed") }
            "output" -> repeat(256) { System.out.print("o".repeat(8192)); System.err.print("e".repeat(8192)) }
            "wait" -> { Thread.sleep(800); print("finished") }
            "child" -> Thread.sleep(8000)
            "descendant" -> {
                val child = ProcessBuilder(javaExecutable(), "-cp", fakeClasspath(), FakeGitProcess::class.java.name, "child").inheritIO().start()
                print(child.pid())
            }
            "environment" -> print(listOf("GIT_DIR", "GIT_INDEX_FILE", "GIT_WORK_TREE", "GIT_TERMINAL_PROMPT").joinToString(":") { System.getenv(it) ?: "null" })
        }
    }
}
