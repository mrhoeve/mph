package nl.hicts.mph.intellij.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class WorkspaceDependencyAnalyzerTest {
    private val workspace = Path.of("build", "workspace-analysis").toAbsolutePath().normalize()
    private val analyzer = WorkspaceDependencyAnalyzer()

    @Test
    fun `describes incoming and outgoing workspace relationships`() {
        val shared = descriptor("shared-api")
        val service = descriptor(
            "sample-service",
            dependencies = setOf(coordinates("shared-api"), coordinates("external-library", "org.external")),
        )

        val relationships = analyzer.relationships(service.project.pomPath, listOf(shared, service))!!

        assertEquals(listOf("external-library", "shared-api"), relationships.dependencies.map { it.coordinates.artifactId })
        assertEquals(null, relationships.dependencies.first().project)
        assertEquals("shared-api", relationships.dependencies.last().project?.artifactId)
        assertTrue(analyzer.relationships(shared.project.pomPath, listOf(shared, service))!!.dependents.single()
            .kinds.contains(MavenReferenceKind.DEPENDENCY))
    }

    @Test
    fun `calculates repository build stages from module dependencies`() {
        val parent = descriptor("platform-parent", repository = "platform")
        val api = descriptor("shared-api", repository = "shared", parent = coordinates("platform-parent"))
        val service = descriptor("sample-service", repository = "service", dependencies = setOf(coordinates("shared-api")))

        val order = analyzer.buildOrder(listOf(service, api, parent))

        assertFalse(order.hasCycles)
        assertEquals(listOf("platform-parent", "shared-api", "sample-service"), order.entries.map { it.project.artifactId })
        assertEquals(listOf(1, 2, 3), order.entries.map(BuildOrderEntry::buildStep))
        assertEquals(listOf("shared-api"), order.entries.last().dependsOn)
    }

    @Test
    fun `reports cyclic repositories in a final stage`() {
        val first = descriptor("first", repository = "first", dependencies = setOf(coordinates("second")))
        val second = descriptor("second", repository = "second", dependencies = setOf(coordinates("first")))

        val order = analyzer.buildOrder(listOf(first, second))

        assertTrue(order.hasCycles)
        assertEquals(1, order.entries.map(BuildOrderEntry::buildStep).distinct().single())
        assertTrue(order.entries.all(BuildOrderEntry::partOfCycle))
    }

    private fun descriptor(
        artifactId: String,
        repository: String = artifactId,
        parent: MavenCoordinates? = null,
        dependencies: Set<MavenCoordinates> = emptySet(),
    ) = MavenProjectDependencyDescriptor(
        project = MavenProjectInfo(
            groupId = "org.example",
            artifactId = artifactId,
            version = "1.0-SNAPSHOT",
            pomPath = workspace.resolve("$repository/$artifactId/pom.xml").toString(),
            gitRootPath = workspace.resolve(repository).toString(),
        ),
        parent = parent,
        dependencies = dependencies,
        managedDependencies = emptySet(),
    )

    private fun coordinates(artifactId: String, groupId: String = "org.example") =
        MavenCoordinates(groupId, artifactId)
}
