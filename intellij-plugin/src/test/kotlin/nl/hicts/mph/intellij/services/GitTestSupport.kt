package nl.hicts.mph.intellij.services

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Only for disposable fixture repositories. Production Git writes must not be killed on a timeout. */
internal object GitTestSupport {
    fun deleteRepository(root: Path) {
        val absolute = root.toAbsolutePath().normalize()
        val temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        require(absolute.startsWith(temporary) && absolute != temporary) { "Fixture cleanup must stay below the temporary directory." }
        // Files.walk does not follow symlinks. Do not hide leaked files/handles behind deleteRecursively's Boolean.
        Files.walk(absolute).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach {
            if (System.getProperty("os.name").startsWith("Windows") && !Files.isSymbolicLink(it)) {
                Files.getFileAttributeView(it, java.nio.file.attribute.DosFileAttributeView::class.java)?.setReadOnly(false)
            }
            Files.delete(it)
        } }
    }

    fun run(root: Path, vararg arguments: String): GitCommandResult {
        val temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        require(root.toAbsolutePath().normalize().startsWith(temporary)) { "Git fixtures must use disposable temporary repositories." }
        val output = Files.createTempFile("mph-test-git-", ".txt")
        try {
            val builder = ProcessBuilder(listOf("git") + arguments).directory(root.toFile())
                .redirectErrorStream(true).redirectOutput(output.toFile())
            builder.environment().apply {
                keys.removeIf { it.startsWith("GIT_", ignoreCase = true) }
                put("GIT_CONFIG_NOSYSTEM", "1")
                put("GIT_CONFIG_GLOBAL", if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")
                put("GIT_TERMINAL_PROMPT", "0")
                put("GCM_INTERACTIVE", "Never")
            }
            val process = builder.start()
            try {
                process.outputStream.close()
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.toHandle().descendants().use { children -> children.forEach { it.destroyForcibly() } }
                    process.destroyForcibly()
                    check(process.waitFor(10, TimeUnit.SECONDS)) { "Fixture Git process could not be stopped." }
                    error("Fixture Git command timed out: ${arguments.firstOrNull()}")
                }
                return GitCommandResult(process.exitValue(), Files.readString(output))
            } finally {
                if (process.isAlive) {
                    process.toHandle().descendants().use { children -> children.forEach { it.destroyForcibly() } }
                    process.destroyForcibly()
                    check(process.waitFor(10, TimeUnit.SECONDS)) { "Fixture Git process remained alive after cleanup." }
                }
                process.inputStream.close()
                process.errorStream.close()
                process.outputStream.close()
            }
        } finally { Files.deleteIfExists(output) }
    }

    fun git(root: Path, vararg arguments: String): String {
        val result = run(root, *arguments)
        check(result.exitCode == 0) { "Fixture git ${arguments.firstOrNull()} failed: ${result.diagnostic}" }
        return result.output
    }
}
