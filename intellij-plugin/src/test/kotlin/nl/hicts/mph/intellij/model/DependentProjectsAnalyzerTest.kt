package nl.hicts.mph.intellij.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

class DependentProjectsAnalyzerTest {
    private val workspace = Path.of("build", "dependent-analysis").toAbsolutePath().normalize()
    private val targetCoordinates = MavenCoordinates("org.example", "shared-api")

    @Test
    fun `finds and classifies every direct reference type`() {
        val target = descriptor("shared-api")
        val parent = descriptor("parent-user", parent = targetCoordinates)
        val dependency = descriptor("dependency-user", dependencies = setOf(targetCoordinates))
        val managed = descriptor("managed-user", managedDependencies = setOf(targetCoordinates))
        val combined = descriptor(
            "combined-user",
            parent = targetCoordinates,
            dependencies = setOf(targetCoordinates),
            managedDependencies = setOf(targetCoordinates),
        )

        val analysis = DependentProjectsAnalyzer().analyze(
            target.project.pomPath,
            listOf(target, parent, dependency, managed, combined),
        )!!

        assertEquals("shared-api", analysis.target.artifactId)
        assertEquals(
            listOf("combined-user", "dependency-user", "managed-user", "parent-user"),
            analysis.dependents.map { it.project.artifactId },
        )
        assertEquals(MavenReferenceKind.PARENT, analysis.dependents.last().references.single())
        assertEquals(
            MavenReferenceKind.entries.toSet(),
            analysis.dependents.first().references,
        )
    }

    @Test
    fun `returns an empty result when target coordinates are incomplete`() {
        val target = descriptor("shared-api", groupId = null)

        val analysis = DependentProjectsAnalyzer().analyze(target.project.pomPath, listOf(target))!!

        assertEquals(emptyList<DependentMavenProject>(), analysis.dependents)
    }

    @Test
    fun `returns null when selected pom is not a linked project`() {
        assertNull(
            DependentProjectsAnalyzer().analyze(
                workspace.resolve("missing/pom.xml").toString(),
                listOf(descriptor("shared-api")),
            ),
        )
    }

    private fun descriptor(
        artifactId: String,
        groupId: String? = "org.example",
        parent: MavenCoordinates? = null,
        dependencies: Set<MavenCoordinates> = emptySet(),
        managedDependencies: Set<MavenCoordinates> = emptySet(),
    ) = MavenProjectDependencyDescriptor(
        project = MavenProjectInfo(
            groupId = groupId,
            artifactId = artifactId,
            version = "1.0-SNAPSHOT",
            pomPath = workspace.resolve("$artifactId/pom.xml").toString(),
            gitRootPath = workspace.resolve(artifactId).toString(),
        ),
        parent = parent,
        dependencies = dependencies,
        managedDependencies = managedDependencies,
    )
}
