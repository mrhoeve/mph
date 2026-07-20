package nl.hicts.mph.intellij.ui

import nl.hicts.mph.intellij.model.DependencyGraphEdge
import nl.hicts.mph.intellij.model.DependencyGraphEdgeKind
import nl.hicts.mph.intellij.model.DependencyGraphNode
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.model.WorkspaceDependencyGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class DependencyGraphPanelTest {
    @Test
    fun `lays out stages from left to right and exports the complete PNG`() {
        val first = node("shared-api", 1)
        val second = node("sample-service", 2)
        val graph = WorkspaceDependencyGraph(
            listOf(first, second),
            listOf(DependencyGraphEdge(first.id, second.id, DependencyGraphEdgeKind.DEPENDENCY)),
        )
        val layout = DependencyGraphLayoutEngine.layout(graph)

        assertTrue(layout.nodeBounds.getValue(second.id).x > layout.nodeBounds.getValue(first.id).x)
        assertEquals(setOf(1, 2), layout.stageHeaders.keys)

        val file = Files.createTempFile("mph-dependency-graph-", ".png")
        try {
            DependencyGraphPngExporter.write(graph, file)
            val image = ImageIO.read(file.toFile())
            assertNotNull(image)
            assertTrue(image.width > 100)
            assertTrue(image.height > 100)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `clears the complete canvas before painting a transformed graph`() {
        val graph = WorkspaceDependencyGraph(listOf(node("sample-service", 1)), emptyList())
        val panel = DependencyGraphPanel(graph) {}
        panel.setSize(900, 600)
        val image = BufferedImage(900, 600, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.MAGENTA
            graphics.fillRect(0, 0, image.width, image.height)
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }

        assertTrue(Color(image.getRGB(2, 2), true) != Color.MAGENTA)
        assertTrue(Color(image.getRGB(897, 597), true) != Color.MAGENTA)
    }

    private fun node(artifactId: String, step: Int) = DependencyGraphNode(
        id = "/workspace/$artifactId/pom.xml",
        project = MavenProjectInfo(
            "org.example",
            artifactId,
            "1.0-SNAPSHOT",
            "/workspace/$artifactId/pom.xml",
            "/workspace/$artifactId",
        ),
        buildStep = step,
        repositoryName = artifactId,
        isRepositoryRoot = true,
    )
}
