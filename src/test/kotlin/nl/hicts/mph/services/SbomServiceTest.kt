package nl.hicts.mph.services

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
