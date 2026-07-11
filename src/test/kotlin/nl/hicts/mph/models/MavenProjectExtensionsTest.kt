package nl.hicts.mph.models

import org.apache.maven.model.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class MavenProjectExtensionsTest {

    @Test
    fun `should return coordinates declared by project`() {
        val project = project(model("org.example", "sample", "1.2.3"))

        assertEquals(InheritableProperty("org.example", false), project.getAppropiateGroupId())
        assertEquals(InheritableProperty("sample", false), project.getAppropiateArtifactId())
        assertEquals(InheritableProperty("1.2.3", false), project.getAppropiateVersion())
        assertEquals("org.example:sample", project.artifact())
    }

    @Test
    fun `should inherit missing coordinates through complete module hierarchy`() {
        val root = project(model("org.example", "root", "2.0.0"))
        val intermediate = project(model(null, "intermediate", null), root)
        val leaf = project(model(null, "leaf", null), intermediate)

        assertEquals(InheritableProperty("org.example", true), leaf.getAppropiateGroupId())
        assertEquals(InheritableProperty("leaf", false), leaf.getAppropiateArtifactId())
        assertEquals(InheritableProperty("2.0.0", true), leaf.getAppropiateVersion())
        assertEquals("org.example:leaf", leaf.artifact())
    }

    @Test
    fun `should return undetermined for missing root coordinates`() {
        val project = project(model(null, null, null))

        assertEquals(InheritableProperty("UNDETERMINED", false), project.getAppropiateGroupId())
        assertEquals(InheritableProperty("UNDETERMINED", false), project.getAppropiateArtifactId())
        assertEquals(InheritableProperty("UNDETERMINED", false), project.getAppropiateVersion())
        assertEquals("UNDETERMINED:UNDETERMINED", project.artifact())
    }

    @Test
    fun `toString should contain artifact parent and pom location`() {
        val parent = project(model("org.example", "parent", "1.0"))
        val child = project(model(null, "child", null), parent)

        val text = child.toString()

        assertEquals(true, text.contains("artifact='org.example:child'"))
        assertEquals(true, text.contains("moduleWithinProject=org.example:parent"))
        assertEquals(true, text.contains("child-pom.xml"))
    }

    private fun model(groupId: String?, artifactId: String?, version: String?) = Model().apply {
        this.groupId = groupId
        this.artifactId = artifactId
        this.version = version
    }

    private fun project(model: Model, parent: MavenProject? = null) =
        MavenProject(parent, File("${model.artifactId ?: "missing"}-pom.xml"), model, emptyList())
}
