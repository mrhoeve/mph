package nl.hicts.mph.services

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import nl.hicts.mph.models.MavenProject
import nl.hicts.mph.models.Settings
import org.apache.maven.model.Dependency
import org.apache.maven.model.DependencyManagement
import org.apache.maven.model.Model
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class MavenProjectServiceOperationsTest {

    @TempDir
    lateinit var tempDir: Path

    private val mavenCommandService = mockk<MavenCommandService>()
    private val gitService = mockk<GitService>()
    private val nexusIqService = mockk<NexusIqService>()
    private val settingsService = mockk<SettingsService>()
    private val sbomService = mockk<SbomService>()
    private val service = MavenProjectService(
        mavenCommandService,
        gitService,
        nexusIqService,
        settingsService,
        sbomService
    )

    @Test
    fun `should upgrade Spring Boot and manage a property override`() {
        val pom = writePom(
            "application/pom.xml",
            """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.5.0</version>
                  </parent>
                  <groupId>org.example</groupId>
                  <artifactId>test-application</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <library.version>1.0.0</library.version>
                  </properties>
                </project>
            """
        )
        justRun { mavenCommandService.runInstallInBackground(any()) }
        justRun { gitService.clearCache() }

        service.upgradeSpringBoot(tempDir, 3, pom.toString(), "3.5.1")
        service.overrideProperty(tempDir, 3, pom.toString(), "library.version", "2.0.0", "Test override")

        val overridden = Files.readString(pom)
        assertTrue(overridden.contains("<version>3.5.1</version>"))
        assertTrue(overridden.contains("<!-- Test override -->"))
        assertTrue(overridden.contains("<library.version>2.0.0</library.version>"))

        service.removePropertyOverride(tempDir, 3, pom.toString(), "library.version")

        assertFalse(Files.readString(pom).contains("library.version"))
        verify(exactly = 3) { mavenCommandService.runInstallInBackground(pom.toFile()) }
        verify(exactly = 1) { gitService.clearCache() }
    }

    @Test
    fun `should reject project operations outside the scanned workspace`() {
        writePom("application/pom.xml", simplePom("test-application"))
        val missing = tempDir.resolve("missing/pom.xml").toString()

        val upgradeError = assertThrows(RuntimeException::class.java) {
            service.upgradeSpringBoot(tempDir, 3, missing, "3.5.1")
        }
        val overrideError = assertThrows(RuntimeException::class.java) {
            service.overrideProperty(tempDir, 3, missing, "library.version", "2.0.0", null)
        }
        justRun { gitService.clearCache() }
        val removeError = assertThrows(RuntimeException::class.java) {
            service.removePropertyOverride(tempDir, 3, missing, "library.version")
        }

        assertTrue(upgradeError.message.orEmpty().contains("Project not found"))
        assertTrue(overrideError.message.orEmpty().contains("Project not found"))
        assertTrue(removeError.message.orEmpty().contains("Project not found"))
    }

    @Test
    fun `should synchronize selected projects and report individual failures`() {
        val first = writePom("first/pom.xml", simplePom("first-project"))
        val second = writePom("second/pom.xml", simplePom("second-project"))
        every { gitService.syncDevelop(first.toFile(), true) } returns "first synchronized"
        every { gitService.syncDevelop(second.toFile(), true) } returns null

        val missing = tempDir.resolve("missing/pom.xml").toAbsolutePath().toString()
        val messages = service.syncDevelop(
            tempDir,
            3,
            listOf(first.toFile().absolutePath, second.toFile().absolutePath, missing),
            mergeDevelop = true
        )

        assertEquals(2, messages.size)
        assertEquals("first synchronized", messages.first())
        assertTrue(messages.last().contains("Project was not found"))
        verify(exactly = 1) { gitService.syncDevelop(first.toFile(), true) }
        verify(exactly = 1) { gitService.syncDevelop(second.toFile(), true) }
    }

    @Test
    fun `should prepare a branch and apply a manual bulk version`() {
        val pom = writePom("application/pom.xml", simplePom("test-application", "1.0.0"))
        justRun { gitService.clearCache() }
        justRun { gitService.prepareBranch(any(), "feature/test") }

        service.bulkUpdateVersions(
            tempDir,
            3,
            BulkVersionUpdate(
                rootProjectPaths = listOf(pom.toFile().absolutePath),
                prefix = "2.0.0",
                updateDependents = false,
                mode = "MANUAL",
                branchName = "feature/test"
            )
        )

        assertTrue(Files.readString(pom).contains("<version>2.0.0</version>"))
        verify(exactly = 1) { gitService.prepareBranch(pom.toFile(), "feature/test") }
    }

    @Test
    fun `should wrap branch preparation failures with project context`() {
        val pom = writePom("application/pom.xml", simplePom("test-application"))
        justRun { gitService.clearCache() }
        every { gitService.prepareBranch(any(), any()) } throws IllegalStateException("branch unavailable")

        val error = assertThrows(RuntimeException::class.java) {
            service.bulkUpdateVersions(
                tempDir,
                3,
                BulkVersionUpdate(
                    rootProjectPaths = listOf(pom.toFile().absolutePath),
                    prefix = "",
                    updateDependents = false,
                    branchName = "feature/test"
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("Failed to prepare Git branch"))
        assertTrue(error.message.orEmpty().contains("branch unavailable"))
    }

    @Test
    fun `should reject a missing project while preparing a branch`() {
        writePom("application/pom.xml", simplePom("test-application"))
        justRun { gitService.clearCache() }
        val missing = tempDir.resolve("missing/pom.xml").toFile().absolutePath

        val error = assertThrows(RuntimeException::class.java) {
            service.bulkUpdateVersions(
                tempDir,
                3,
                BulkVersionUpdate(
                    rootProjectPaths = listOf(missing),
                    prefix = "",
                    updateDependents = false,
                    branchName = "feature/test"
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("Project was not found"))
        assertTrue(error.message.orEmpty().contains(missing))
    }

    @Test
    fun `should skip version editing for a branch-only update`() {
        val pom = writePom("application/pom.xml", simplePom("test-application", "1.0.0"))
        justRun { gitService.clearCache() }
        justRun { gitService.prepareBranch(any(), "feature/test") }

        service.bulkUpdateVersions(
            tempDir,
            3,
            BulkVersionUpdate(
                rootProjectPaths = listOf(pom.toFile().absolutePath),
                prefix = "",
                updateDependents = true,
                branchName = "feature/test"
            )
        )

        assertTrue(Files.readString(pom).contains("<version>1.0.0</version>"))
        verify(exactly = 1) { gitService.prepareBranch(pom.toFile(), "feature/test") }
    }

    @Test
    fun `should support removing and adding version prefixes`() {
        val pom = writePom("application/pom.xml", simplePom("test-application", "TEST-1.0.0"))
        justRun { gitService.clearCache() }

        service.bulkUpdateVersions(
            tempDir,
            3,
            BulkVersionUpdate(listOf(pom.toFile().absolutePath), "TEST-", false, mode = "REMOVE_PREFIX")
        )
        assertTrue(Files.readString(pom).contains("<version>1.0.0</version>"))

        service.bulkUpdateVersions(
            tempDir,
            3,
            BulkVersionUpdate(listOf(pom.toFile().absolutePath), "NEXT-", false, mode = "ADD_PREFIX")
        )
        assertTrue(Files.readString(pom).contains("<version>NEXT-1.0.0</version>"))
    }

    @Test
    fun `should propagate updated versions to parent dependency and managed property references`() {
        val parent = writePom("parent/pom.xml", simplePom("shared-parent", "1.0.0"))
        val library = writePom("library/pom.xml", simplePom("shared-library", "1.0.0"))
        val consumer = writePom(
            "consumer/pom.xml",
            """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.example</groupId>
                    <artifactId>shared-parent</artifactId>
                    <version>1.0.0</version>
                    <relativePath>../parent/pom.xml</relativePath>
                  </parent>
                  <artifactId>consumer</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <shared-library.version>1.0.0</shared-library.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId>
                      <artifactId>shared-library</artifactId>
                      <version>${'$'}{shared-library.version}</version>
                    </dependency>
                  </dependencies>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>shared-library</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
            """
        )
        justRun { gitService.clearCache() }

        service.updateVersions(tempDir, 3, "org.example", "shared-parent", "2.0.0")
        service.updateVersions(tempDir, 3, "org.example", "shared-library", "3.0.0")

        val updatedConsumer = Files.readString(consumer)
        assertTrue(updatedConsumer.contains("<artifactId>shared-parent</artifactId>\n    <version>2.0.0</version>"))
        assertTrue(updatedConsumer.contains("<shared-library.version>3.0.0</shared-library.version>"))
        assertTrue(updatedConsumer.contains("<artifactId>shared-library</artifactId>\n        <version>3.0.0</version>"))
        assertTrue(Files.exists(parent))
        assertTrue(Files.exists(library))
    }

    @Test
    fun `should find a project tag and reject an unknown project`() {
        val pom = writePom("application/pom.xml", simplePom("test-application"))
        every { gitService.getLatestTagInfo(pom.toFile()) } returns TagInfo("1.0.0", "v1.0.0")

        assertEquals(TagInfo("1.0.0", "v1.0.0"), service.getLatestTag(tempDir, 3, pom.toFile().absolutePath))
        assertThrows(IllegalArgumentException::class.java) {
            service.getLatestTag(tempDir, 3, tempDir.resolve("missing/pom.xml").toString())
        }
    }

    @Test
    fun `should analyze Spring BOM properties and attach Nexus IQ violations`() {
        val pom = writePom(
            "application/pom.xml",
            """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>test-application</artifactId>
                  <version>1.0.0</version>
                  <properties><library-core.version>1.2.3</library-core.version></properties>
                  <dependencies><dependency>
                    <groupId>org.example</groupId><artifactId>library-core</artifactId>
                    <version>${'$'}{library-core.version}</version>
                  </dependency></dependencies>
                </project>
            """
        )
        val model = Model().apply {
            groupId = "org.example"
            artifactId = "test-application"
            version = "1.0.0"
            properties.setProperty("library-core.version", "1.2.3")
            dependencies = listOf(Dependency().apply {
                groupId = "org.example"
                artifactId = "library-core"
                version = "1.2.3"
            })
            dependencyManagement = DependencyManagement().apply {
                dependencies = listOf(Dependency().apply {
                    groupId = "org.springframework.boot"
                    artifactId = "spring-boot-dependencies"
                    version = "4.1.0"
                    type = "pom"
                    scope = "import"
                })
            }
        }
        val project = MavenProject(null, pom.toFile(), model, emptyList())
        every { settingsService.loadSettings() } returns Settings(tempDir, 3, nexusIqUrl = "https://nexus.example.org")
        every { nexusIqService.extractNexusIqAppId(pom.parent.toString(), any()) } returns "test-application"
        every { nexusIqService.getVulnerabilitiesBatch(any()) } returns listOf(
            NexusIqPolicyViolation("pkg:maven:library-core:1.2.3", 7, "Test policy", emptyList(), "1.2.4")
        )
        every { nexusIqService.getReportUrl("test-application", any()) } returns "https://nexus.example.org/report"
        every { gitService.getGitStatus(pom.toFile()) } returns GitStatus("main", 0, 0)

        val analysis = service.analyzeProject(
            project,
            listOf(project),
            mapOf("org.example:test-application:1.0.0" to project),
            resolveProps = true,
            isRoot = true
        )

        assertTrue(analysis.hasSpringBootParent)
        assertEquals("4.1.0", analysis.springBootVersion)
        assertEquals("test-application", analysis.nexusIqResult?.applicationPublicId)
        assertEquals(1, analysis.managedProperties.single { it.name == "library-core.version" }.nexusIqViolations.size)
    }

    @Test
    fun `should retain raw properties when effective model resolution fails`() {
        val pom = writePom(
            "application/pom.xml",
            """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId><artifactId>test-application</artifactId><version>1.0.0</version>
                  <properties><!-- Test fallback --><library.version>1.2.3</library.version></properties>
                </project>
            """
        )
        val model = Model().apply {
            groupId = "org.example"
            artifactId = "test-application"
            version = "1.0.0"
            properties.setProperty("library.version", "1.2.3")
        }
        val project = MavenProject(null, pom.toFile(), model, emptyList())
        val resolver = mockk<MavenModelResolver>()
        every { resolver.resolveModelResult(any<File>()) } throws IllegalStateException("model unavailable")
        MavenProjectService::class.java.getDeclaredField("modelResolver").apply {
            isAccessible = true
            set(service, resolver)
        }
        every { settingsService.loadSettings() } returns Settings(tempDir, 3)
        every { nexusIqService.extractNexusIqAppId(any(), any()) } returns null

        val analysis = service.analyzeProject(
            project,
            listOf(project),
            mapOf("org.example:test-application:1.0.0" to project),
            resolveProps = true
        )

        assertEquals("model unavailable", analysis.error)
        assertEquals("1.2.3", analysis.managedProperties.single().value)
        assertEquals("Test fallback", analysis.managedProperties.single().comment)
    }

    @Test
    fun `should export only root projects to a readable workbook`() {
        val root = analysis("root-project", isRoot = true, buildStep = 2, dependsOn = listOf("dependency-a", "dependency-b"))
        val module = analysis("module-project", isRoot = false)

        val bytes = service.exportToExcel(listOf(root, module))

        XSSFWorkbook(ByteArrayInputStream(bytes)).use { workbook ->
            val sheet = workbook.getSheet("Build Order")
            assertEquals("Build Step", sheet.getRow(0).getCell(0).stringCellValue)
            assertEquals(2.0, sheet.getRow(1).getCell(0).numericCellValue)
            assertEquals("root-project", sheet.getRow(1).getCell(1).stringCellValue)
            assertEquals("1.0.0", sheet.getRow(1).getCell(2).stringCellValue)
            assertEquals("dependency-a, dependency-b", sheet.getRow(1).getCell(3).stringCellValue)
            assertEquals(2, sheet.physicalNumberOfRows)
        }
    }

    private fun analysis(
        artifactId: String,
        isRoot: Boolean,
        buildStep: Int = 0,
        dependsOn: List<String> = emptyList()
    ) = ProjectAnalysis(
        groupId = "org.example",
        artifactId = artifactId,
        version = "1.0.0",
        path = tempDir.resolve(artifactId).toString(),
        modules = emptyList(),
        usages = emptyList(),
        isRoot = isRoot,
        buildStep = buildStep,
        dependsOn = dependsOn
    )

    private fun simplePom(artifactId: String, version: String = "1.0.0") = """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>org.example</groupId>
          <artifactId>$artifactId</artifactId>
          <version>$version</version>
        </project>
    """

    private fun writePom(relativePath: String, content: String): Path {
        val path = tempDir.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content.trimIndent())
        return path
    }
}
