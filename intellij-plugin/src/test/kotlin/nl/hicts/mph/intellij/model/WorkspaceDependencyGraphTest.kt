package nl.hicts.mph.intellij.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class WorkspaceDependencyGraphTest {
    private val workspace = Path.of("build", "graph-workspace").toAbsolutePath().normalize()

    @Test
    fun `preserves modules dependencies stages and focused neighbourhood`() {
        val parent = descriptor("platform", "platform", pom = "pom.xml")
        val module = descriptor("platform-api", "platform", pom = "api/pom.xml", parent = coordinates("platform"))
        val service = descriptor("sample-service", "service", dependencies = setOf(coordinates("platform-api")))
        val unrelated = descriptor("unrelated", "unrelated")
        val descriptors = listOf(parent, module, service, unrelated)
        val order = WorkspaceDependencyAnalyzer().buildOrder(descriptors)

        val graph = WorkspaceDependencyGraphBuilder().build(descriptors, order)
        val moduleNode = graph.nodes.single { it.project.artifactId == "platform-api" }
        val parentNode = graph.nodes.single { it.project.artifactId == "platform" }
        val serviceNode = graph.nodes.single { it.project.artifactId == "sample-service" }

        assertEquals(parentNode.id, moduleNode.parentId)
        assertTrue(graph.edges.contains(DependencyGraphEdge(parentNode.id, moduleNode.id, DependencyGraphEdgeKind.MODULE)))
        assertTrue(graph.edges.contains(DependencyGraphEdge(moduleNode.id, serviceNode.id, DependencyGraphEdgeKind.DEPENDENCY)))
        assertTrue(serviceNode.buildStep > moduleNode.buildStep)

        val focused = graph.focus(moduleNode.id)
        assertEquals(setOf("platform", "platform-api", "sample-service"), focused.nodes.map { it.project.artifactId }.toSet())
        assertEquals(moduleNode.id, focused.focusedNodeId)
        assertEquals(graph.nodes.size, graph.focus(null).nodes.size)
    }

    private fun descriptor(
        artifactId: String,
        repository: String,
        pom: String = "pom.xml",
        parent: MavenCoordinates? = null,
        dependencies: Set<MavenCoordinates> = emptySet(),
    ) = MavenProjectDependencyDescriptor(
        MavenProjectInfo(
            "org.example",
            artifactId,
            "1.0-SNAPSHOT",
            workspace.resolve(repository).resolve(pom).toString(),
            workspace.resolve(repository).toString(),
        ),
        parent,
        dependencies,
        emptySet(),
    )

    private fun coordinates(artifactId: String) = MavenCoordinates("org.example", artifactId)
}
