package nl.hicts.mph.intellij.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Path

class ProjectSnapshotBuilderTest {
    private val workspace = Path.of("build", "test-workspace").toAbsolutePath().normalize()

    @Test
    fun `groups projects by their nearest git root`() {
        val serviceRoot = workspace.resolve("service")
        val nestedRoot = serviceRoot.resolve("nested")
        val projects = listOf(
            descriptor("service", serviceRoot.resolve("pom.xml")),
            descriptor("nested", nestedRoot.resolve("pom.xml")),
        )

        val snapshot = ProjectSnapshotBuilder().build(
            projects,
            listOf(serviceRoot.toString(), nestedRoot.toString()),
        )

        assertEquals(2, snapshot.repositoryCount)
        val nestedGroup = snapshot.groups.single { it.rootPath == nestedRoot.toString() }
        val serviceGroup = snapshot.groups.single { it.rootPath == serviceRoot.toString() }
        assertEquals("nested", nestedGroup.projects.single().artifactId)
        assertEquals("service", serviceGroup.projects.single().artifactId)
    }

    @Test
    fun `keeps projects outside git repositories visible`() {
        val project = descriptor("standalone", workspace.resolve("standalone/pom.xml"))

        val snapshot = ProjectSnapshotBuilder().build(listOf(project), emptyList())

        assertEquals(1, snapshot.projectCount)
        assertEquals(0, snapshot.repositoryCount)
        assertNull(snapshot.groups.single().rootPath)
    }

    @Test
    fun `sorts projects consistently within a repository`() {
        val root = workspace.resolve("repository")
        val projects = listOf(
            descriptor("z-service", root.resolve("z/pom.xml")),
            descriptor("a-service", root.resolve("a/pom.xml")),
        )

        val snapshot = ProjectSnapshotBuilder().build(projects, listOf(root.toString()))

        assertEquals(listOf("a-service", "z-service"), snapshot.groups.single().projects.map { it.artifactId })
    }

    private fun descriptor(artifactId: String, pomPath: Path) = MavenProjectDescriptor(
        groupId = "com.example",
        artifactId = artifactId,
        version = "1.0-SNAPSHOT",
        pomPath = pomPath.toString(),
    )
}
