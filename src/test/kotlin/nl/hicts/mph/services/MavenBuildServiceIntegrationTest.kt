package nl.hicts.mph.services

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MavenBuildServiceIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private val projectService = mockk<MavenProjectService>()

    @Test
    fun `should execute selected project and publish complete successful progress`() {
        val projectDirectory = Files.createDirectories(tempDir.resolve("sample"))
        val pom = projectDirectory.resolve("pom.xml")
        Files.writeString(pom, "<project/>")
        createWrapper(tempDir, 0)
        val project = analysis("sample", pom)
        every { projectService.scanAndAnalyze(tempDir, 3) } returns listOf(project)
        val service = MavenBuildService(projectService)
        val events = CopyOnWriteArrayList<ProjectProgress>()
        val completed = CountDownLatch(1)
        val subscription = service.getBuildEvents().subscribe { event ->
            events.add(event)
            if (event.status == BuildStatus.SUCCESS || event.status == BuildStatus.FAILED) completed.countDown()
        }

        service.startBuild(
            tempDir.toString(),
            3,
            listOf(pom.toString()),
            BuildOptions(skipUTs = false, skipITs = true, parallel = false, maxParallel = 1)
        )

        assertEquals(true, completed.await(10, TimeUnit.SECONDS), "Build did not complete within timeout")
        val statusEvents = events.filter { it.logLine == null }.map { it.status }
        assertEquals(listOf(BuildStatus.PENDING, BuildStatus.RUNNING, BuildStatus.SUCCESS), statusEvents)
        assertEquals(listOf("install -DskipUTs=false -DskipITs=true"), service.getLogs(pom.toString()))
        subscription.dispose()
        service.stopBuild()
    }

    @Test
    fun `should publish failed result and retain output for nonzero wrapper exit`() {
        val projectDirectory = Files.createDirectories(tempDir.resolve("failure"))
        val pom = projectDirectory.resolve("pom.xml")
        Files.writeString(pom, "<project/>")
        createWrapper(tempDir, 4)
        val project = analysis("failure", pom)
        every { projectService.scanAndAnalyze(tempDir, 2) } returns listOf(project)
        val service = MavenBuildService(projectService)
        val terminal = CopyOnWriteArrayList<ProjectProgress>()
        val completed = CountDownLatch(1)
        val subscription = service.getBuildEvents().subscribe { event ->
            if (event.status == BuildStatus.FAILED) {
                terminal.add(event)
                completed.countDown()
            }
        }

        service.startBuild(tempDir.toString(), 2, listOf(pom.toString()), BuildOptions(parallel = false))

        assertEquals(true, completed.await(10, TimeUnit.SECONDS), "Failed build did not complete within timeout")
        assertEquals(1, terminal.size)
        assertEquals(ProjectProgress(pom.toString(), "failure", BuildStatus.FAILED), terminal.single())
        assertEquals(listOf("install -DskipUTs=true -DskipITs=true"), service.getLogs(pom.toString()))
        subscription.dispose()
        service.stopBuild()
    }

    private fun analysis(artifactId: String, pom: Path) = ProjectAnalysis(
        groupId = "org.example",
        artifactId = artifactId,
        version = "1.0.0",
        path = pom.toString(),
        modules = emptyList(),
        usages = emptyList(),
        isRoot = true
    )

    private fun createWrapper(directory: Path, exitCode: Int) {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val wrapper = directory.resolve(if (windows) "mvnw.cmd" else "mvnw")
        val content = if (windows) {
            "@echo off\r\necho %*\r\nexit /b $exitCode\r\n"
        } else {
            "#!/bin/sh\necho \"\$*\"\nexit $exitCode\n"
        }
        Files.writeString(wrapper, content)
        wrapper.toFile().setExecutable(true)
    }
}
