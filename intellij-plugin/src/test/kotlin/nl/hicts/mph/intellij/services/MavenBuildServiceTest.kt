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

    @Test
    fun `sequential builds install prerequisites before dependents regardless of selection order`() {
        val library = project("C:/workspace/library/pom.xml").copy(artifactId = "library")
        val application = project("C:/workspace/application/pom.xml").copy(artifactId = "application")

        val stages = MavenBuildService().executionStages(
            listOf(application, library, application),
            MavenBuildOptions(parallel = false, buildSteps = mapOf(
                library.pomPath to 1, application.pomPath to 2,
            )),
        )

        assertEquals(listOf(listOf(library), listOf(application)), stages)
    }

    @Test
    fun `failed prerequisites skip direct and transitive dependents but not independent builds`() {
        for (parallel in listOf(false, true)) {
            val library = project("library/pom.xml")
            val application = project("application/pom.xml")
            val distribution = project("distribution/pom.xml")
            val independent = project("independent/pom.xml")
            val started = mutableListOf<String>()
            val options = MavenBuildOptions(parallel = parallel, buildSteps = mapOf(
                library.pomPath to 1, application.pomPath to 2, distribution.pomPath to 3, independent.pomPath to 2,
            ), prerequisites = mapOf(application.pomPath to setOf(library.pomPath), distribution.pomPath to setOf(application.pomPath)))
            val results = MavenBuildService().executeBuild(
                listOf(distribution, independent, application, library), options, MavenBuildListener { _, _, _ -> },
            ) { stage ->
                stage.map {
                    started += it.pomPath
                    MavenProjectBuildResult(it, if (it == library) MavenBuildStatus.FAILED else MavenBuildStatus.SUCCESS, 1)
                }
            }.associate { it.project.pomPath to it.status }
            assertEquals(listOf(library.pomPath, independent.pomPath), started)
            assertEquals(MavenBuildStatus.SKIPPED, results[application.pomPath])
            assertEquals(MavenBuildStatus.SKIPPED, results[distribution.pomPath])
            assertEquals(MavenBuildStatus.SUCCESS, results[independent.pomPath])
        }
    }

    @Test
    fun `cycles are rejected before any build starts in either mode`() {
        val first = project("first/pom.xml")
        val second = project("second/pom.xml")
        for (parallel in listOf(false, true)) {
            assertThrows(IllegalStateException::class.java) {
                MavenBuildService().executeBuild(listOf(first, second), MavenBuildOptions(
                    parallel = parallel,
                    prerequisites = mapOf(first.pomPath to setOf(second.pomPath), second.pomPath to setOf(first.pomPath)),
                ), MavenBuildListener { _, _, _ -> }) { error("No process should start") }
            }
        }
    }

    @Test
    fun `successful and unselected prerequisites do not block builds`() {
        val library = project("library/pom.xml")
        val application = project("application/pom.xml")
        val result = MavenBuildService().executeBuild(listOf(application, library), MavenBuildOptions(
            buildSteps = mapOf(library.pomPath to 1, application.pomPath to 2),
            prerequisites = mapOf(application.pomPath to setOf(library.pomPath, "unselected/pom.xml")),
        ), MavenBuildListener { _, _, _ -> }) { stage -> stage.map { MavenProjectBuildResult(it, MavenBuildStatus.SUCCESS, 0) } }
        assertEquals(listOf(library, application), result.map { it.project })
        assertTrue(result.all { it.status == MavenBuildStatus.SUCCESS })
    }

    private fun project(pomPath: String) = MavenProjectInfo(
        groupId = "org.example",
        artifactId = "sample-service",
        version = "1.0-SNAPSHOT",
        pomPath = pomPath,
        gitRootPath = null,
    )
}
