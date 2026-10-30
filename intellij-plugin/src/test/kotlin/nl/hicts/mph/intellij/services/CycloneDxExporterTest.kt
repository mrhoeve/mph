package nl.hicts.mph.intellij.services

import nl.hicts.mph.intellij.model.MavenProjectInfo
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

class CycloneDxExporterTest {
    private val analysis = SbomAnalysis(
        MavenProjectInfo("org.example", "sample-service", "1.0.0", "/workspace/pom.xml", "/workspace"),
        listOf(
            SbomComponent(
                "org.example",
                "direct-library",
                "2.0.0",
                "jar",
                "compile",
                true,
                listOf(SbomComponent("org.example", "transitive-library", "3.0.0", "jar", "runtime", true, emptyList())),
            ),
        ),
    )

    @Test
    fun `exports CycloneDX json with dependency relationships`() {
        val json = CycloneDxExporter.json(analysis, Instant.EPOCH, UUID(0, 1))

        assertTrue(json.contains("\"bomFormat\":\"CycloneDX\""))
        assertTrue(json.contains("direct-library"))
        assertTrue(json.contains("transitive-library"))
        assertTrue(json.contains("\"dependsOn\""))
    }

    @Test
    fun `exports well formed CycloneDX xml`() {
        val xml = CycloneDxExporter.xml(analysis, Instant.EPOCH, UUID(0, 1))

        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))
        assertTrue(xml.contains("http://cyclonedx.org/schema/bom/1.5"))
        assertTrue(xml.contains("transitive-library"))
    }

    @Test
    fun `uses resolved dependencies when IntelliJ has no dependency tree cached`() {
        val fallback = listOf(
            SbomComponent("org.example", "fallback-library", "1.0.0", "jar", "compile", true, emptyList()),
        )

        assertEquals(fallback, SbomDependencyResolver.preferTree(emptyList(), fallback))
    }

    @Test
    fun `prefers the transitive dependency tree when available`() {
        val tree = analysis.dependencies
        val fallback = listOf(
            SbomComponent("org.example", "fallback-library", "1.0.0", "jar", "compile", true, emptyList()),
        )

        assertEquals(tree, SbomDependencyResolver.preferTree(tree, fallback))
    }
}
