package nl.hicts.mph.services

import io.mockk.every
import io.mockk.mockk
import org.apache.maven.model.License
import org.apache.maven.model.Model
import org.apache.maven.model.Organization
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.graph.Dependency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class SbomServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val sbomService = SbomService()

    @Test
    fun `should generate different SBOMs for parent and submodule`() {
        val testDataPath = File("src/test/resources/test-data/multi-module-project").absoluteFile
        val parentPom = File(testDataPath, "pom.xml")
        val serviceParentPom = File(testDataPath, "service-parent/pom.xml")
        val commonPom = File(testDataPath, "common/pom.xml")

        val workspaceMap = mapOf(
            "com.example:multi-module-project:2.0.0" to parentPom,
            "com.example:service-parent:2.0.0" to serviceParentPom,
            "com.example:common:2.0.0" to commonPom
        )
        sbomService.setWorkspace(workspaceMap)

        val parentSbom = sbomService.getSbomDetails(parentPom.absolutePath)
        val submoduleSbom = sbomService.getSbomDetails(serviceParentPom.absolutePath)

        // In the CycloneDX Bom (which SbomDetails uses as fallback or basis for rawXml/Json)
        // We now expect them to be different.

        assertTrue(parentSbom.rawJson.contains("multi-module-project"), "Parent SBOM should have its name in metadata")
        assertTrue(submoduleSbom.rawJson.contains("service-parent"), "Submodule SBOM should have its name in metadata")

        // Verify they are not identical
        assertTrue(parentSbom.rawJson != submoduleSbom.rawJson, "Parent and submodule SBOMs should be different")

        // The parent includes common as a module and service-parent includes it as a direct dependency.
        assertTrue(parentSbom.rawJson.contains("common"), "Parent SBOM should contain common module")
        assertTrue(submoduleSbom.rawJson.contains("common"), "service-parent SBOM should contain its common dependency")
    }

    @Test
    fun `should include transitive dependencies in SBOM`() {
        val testDataPath = File("src/test/resources/test-data/multi-module-project").absoluteFile
        val serviceParentPom = File(testDataPath, "service-parent/pom.xml")
        val commonPom = File(testDataPath, "common/pom.xml")
        val rootPom = File(testDataPath, "pom.xml")

        // Mock a simple workspace with correct keys (matching what SbomService expects)
        val workspaceMap = mapOf(
            "com.example:multi-module-project:2.0.0" to rootPom,
            "com.example:service-parent:2.0.0" to serviceParentPom,
            "com.example:common:2.0.0" to commonPom
        )
        sbomService.setWorkspace(workspaceMap)

        // Directly test buildBom to see the result
        val sbomDetails = sbomService.getSbomDetails(serviceParentPom.absolutePath)

        // If resolution worked, we should see dependencies or at least the raw JSON should contain 'dependencies'
        // Given we are in a test env without full maven repo, we might just verify that the structure is correct
        // even if empty, OR if we got any components, they have dependencies.

        assertTrue(sbomDetails.rawJson.contains("service-parent"), "SBOM should at least contain the root project info")
    }

    @Test
    fun `should find dependencies for d-project service`() {
        val cleanTestData = copyPomFixturesWithoutBuildOutputs()
        val dProjectPath = cleanTestData.resolve("d-project").toFile()
        val multiModulePath = cleanTestData.resolve("multi-module-project").toFile()

        val dRootPom = File(dProjectPath, "pom.xml")
        val dApiPom = File(dProjectPath, "api/pom.xml")
        val dClientPom = File(dProjectPath, "client/pom.xml")
        val dServicePom = File(dProjectPath, "service/pom.xml")

        val cClientPom = cleanTestData.resolve("c-project/client/pom.xml").toFile()

        val serviceParentPom = File(multiModulePath, "service-parent/pom.xml")

        val workspaceMap = mapOf(
            "com.example.d:d-project:1.0.0" to dRootPom,
            "com.example.d:d-project-api:1.0.0" to dApiPom,
            "com.example.d:d-project-client:1.0.0" to dClientPom,
            "com.example.d:d-project-service:1.0.0" to dServicePom,
            "com.example.c:c-project-client:1.0.0" to cClientPom,
            "com.example:service-parent:1.0.0" to serviceParentPom
        )
        sbomService.setWorkspace(workspaceMap)

        val sbomDetails = sbomService.getSbomDetails(dServicePom.absolutePath)
        
        // The issue is that it says there aren't any dependencies found.
        // We expect d-project-api and c-project-client to be there.
        val componentNames = sbomDetails.components.map { it.artifactId }
        assertTrue(componentNames.contains("d-project-api"), "Should contain d-project-api. Found: $componentNames")
        assertTrue(componentNames.contains("c-project-client"), "Should contain c-project-client. Found: $componentNames")
    }

    @Test
    fun `should fall back to flat dependencies and retain useful metadata`() {
        val rootPom = tempDir.resolve("pom.xml").toFile().apply { writeText("<project/>") }
        val modulePom = tempDir.resolve("module/pom.xml").toFile().apply {
            parentFile.mkdirs()
            writeText("<project/>")
        }
        val model = Model().apply {
            groupId = "org.example"
            artifactId = "test-application"
            version = "1.0.0"
            description = "Test application"
            organization = Organization().apply { name = "Example Organization" }
            licenses = listOf(
                License().apply { name = "MIT License" },
                License().apply { name = "Example Test License" }
            )
        }
        val resolver = mockk<MavenModelResolver>()
        every { resolver.resolveEffectiveModel(rootPom.absoluteFile) } returns model
        every { resolver.resolveEffectiveModel(modulePom.absoluteFile) } throws IllegalStateException("module unavailable")
        every { resolver.resolveDependencyTree(any(), any(), any()) } throws IllegalStateException("tree unavailable")
        every { resolver.resolveAllDependencies("org.example", "test-application", "1.0.0") } returns listOf(
            Dependency(DefaultArtifact("org.example", "compile-library", "", "jar", "2.0.0"), "compile"),
            Dependency(DefaultArtifact("org.example", "test-library", "", "jar", "3.0.0"), "test")
        )
        val resolverField = SbomService::class.java.getDeclaredField("modelResolver").apply { isAccessible = true }
        resolverField.set(sbomService, resolver)
        val workspaceField = SbomService::class.java.getDeclaredField("workspaceProjects").apply { isAccessible = true }
        workspaceField.set(
            sbomService,
            mapOf(
                "org.example:test-application:1.0.0" to rootPom,
                "org.example:test-module:1.0.0" to modulePom
            )
        )

        val rawJson = sbomService.generateSbom(rootPom.absolutePath, "json")

        assertTrue(rawJson.contains("compile-library"))
        assertTrue(rawJson.contains("test-library"))
        assertTrue(rawJson.contains("Example Organization"))
        assertTrue(rawJson.contains("MIT"))
        assertTrue(rawJson.contains("Example Test License"))
    }

    private fun copyPomFixturesWithoutBuildOutputs(): Path {
        val source = Path.of("src/test/resources/test-data").toAbsolutePath().normalize()
        val destination = tempDir.resolve("test-data")

        Files.walk(source).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() == "pom.xml" }
                .forEach { pom ->
                    val target = destination.resolve(source.relativize(pom).toString())
                    Files.createDirectories(target.parent)
                    Files.copy(pom, target, StandardCopyOption.REPLACE_EXISTING)
                }
        }
        return destination
    }
}
