package nl.hicts.mph.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class MavenCommandServiceIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private val service = MavenCommandService()

    @Test
    fun `should execute repository wrapper and return its exit code`() {
        val projectDir = Files.createDirectories(tempDir.resolve("project/module"))
        createWrapper(tempDir, 7)

        val exitCode = service.runMavenCommandInBackground(projectDir.toFile(), listOf("verify"), "test-command")
            .get(10, TimeUnit.SECONDS)

        assertEquals(7, exitCode)
    }

    @Test
    fun `should complete exceptionally when process cannot be started`() {
        val missingDirectory = tempDir.resolve("missing").toFile()

        val exception = assertThrows(ExecutionException::class.java) {
            service.runMavenCommandInBackground(missingDirectory, listOf("verify"), "missing-command")
                .get(10, TimeUnit.SECONDS)
        }

        assertEquals(true, exception.cause is java.io.IOException)
    }

    private fun createWrapper(directory: Path, exitCode: Int) {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val wrapper = directory.resolve(if (windows) "mvnw.cmd" else "mvnw")
        val content = if (windows) {
            "@echo off\r\necho wrapper-output\r\nexit /b $exitCode\r\n"
        } else {
            "#!/bin/sh\necho wrapper-output\nexit $exitCode\n"
        }
        Files.writeString(wrapper, content)
        wrapper.toFile().setExecutable(true)
    }
}
