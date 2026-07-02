package nl.hicts.mph.services

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class BulkUpdateCurrentVersionTest {

    private val mavenCommandService = mockk<MavenCommandService>()
    private val gitService = mockk<GitService>()
    private val service = MavenProjectService(mavenCommandService, gitService)

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `should update dependents to current version in CURRENT mode`() {
        // Prepare test data
        val projectADir = tempDir.resolve("project-a").toFile().apply { mkdirs() }
        val projectAPom = File(projectADir, "pom.xml").apply {
            writeText("""
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>project-a</artifactId>
                    <version>1.2.3</version>
                </project>
            """.trimIndent())
        }

        val projectBDir = tempDir.resolve("project-b").toFile().apply { mkdirs() }
        val projectBPom = File(projectBDir, "pom.xml").apply {
            writeText("""
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>project-b</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <project-a.version>1.0.0</project-a.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>project-a</artifactId>
                            <version>${'$'}{project-a.version}</version>
                        </dependency>
                    </dependencies>
                </project>
            """.trimIndent())
        }

        every { gitService.getLatestTag(any()) } returns null
        io.mockk.justRun { gitService.clearCache() }

        // Execute bulk update in CURRENT mode
        service.bulkUpdateVersions(
            basePath = tempDir,
            maxDepth = 2,
            rootProjectPaths = listOf(projectAPom.absolutePath),
            prefix = "",
            updateDependents = true,
            mode = "CURRENT",
            updateProjects = false
        )

        // Verify results
        val updatedBPom = projectBPom.readText()
        assertTrue(updatedBPom.contains("<project-a.version>1.2.3</project-a.version>"), "project-b should be updated to use project-a's current version 1.2.3. Content:\n${'$'}updatedBPom")
        
        val updatedAPom = projectAPom.readText()
        assertTrue(updatedAPom.contains("<version>1.2.3</version>"), "project-a version should remain 1.2.3")
    }
}
