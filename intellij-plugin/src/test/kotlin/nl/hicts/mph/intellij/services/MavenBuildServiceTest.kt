package nl.hicts.mph.intellij.services

import nl.hicts.mph.intellij.model.MavenProjectInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MavenBuildServiceTest {
    @Test
    fun `uses the nearest Maven wrapper and requested test options`() {
        val repository = Files.createTempDirectory("mph-maven-build-")
        val module = Files.createDirectories(repository.resolve("module"))
        val wrapper = Files.writeString(repository.resolve("mvnw.cmd"), "@echo off")
        try {
            val command = MavenBuildService().commandLine(
                project(module.resolve("pom.xml").toString()),
                MavenBuildOptions(listOf("verify"), skipUnitTests = false, skipIntegrationTests = true),
                "Windows 11",
            )

            assertEquals("cmd.exe", command.exePath)
            assertTrue(command.parametersList.list.contains(wrapper.toString()))
            assertTrue(command.parametersList.list.contains("verify"))
            assertTrue(command.parametersList.list.contains("-DskipITs=true"))
            assertTrue(command.parametersList.list.none { it.startsWith("-DskipTests") })
            assertEquals(module.toFile(), command.workDirectory)
        } finally {
            Files.deleteIfExists(wrapper)
            Files.deleteIfExists(module)
            Files.deleteIfExists(repository)
        }
    }

    @Test
    fun `falls back to Maven and rejects an empty goal list`() {
        val directory = Files.createTempDirectory("mph-maven-fallback-")
        try {
            val project = project(directory.resolve("pom.xml").toString())
            val command = MavenBuildService().commandLine(project, MavenBuildOptions(listOf("install")), "Linux")

            assertEquals("mvn", command.exePath)
            assertThrows(IllegalArgumentException::class.java) {
                MavenBuildService().commandLine(project, MavenBuildOptions(emptyList()), "Linux")
            }
        } finally {
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `groups parallel builds by dependency stage`() {
        val first = project("C:/workspace/first/pom.xml").copy(artifactId = "first")
        val second = project("C:/workspace/second/pom.xml").copy(artifactId = "second")
        val third = project("C:/workspace/third/pom.xml").copy(artifactId = "third")
        val service = MavenBuildService()

        val stages = service.executionStages(
            listOf(third, first, second),
            MavenBuildOptions(parallel = true, maxParallel = 2, buildSteps = mapOf(
                first.pomPath to 1, second.pomPath to 1, third.pomPath to 2,
            )),
        )

        assertEquals(listOf(listOf(first, second), listOf(third)), stages)
        assertEquals(3, service.executionStages(listOf(first, second, third), MavenBuildOptions()).size)
    }

    private fun project(pomPath: String) = MavenProjectInfo(
        groupId = "org.example",
        artifactId = "sample-service",
        version = "1.0-SNAPSHOT",
        pomPath = pomPath,
        gitRootPath = null,
    )
}
