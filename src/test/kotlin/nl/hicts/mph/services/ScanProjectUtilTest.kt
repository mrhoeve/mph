package nl.hicts.mph.services

import nl.hicts.mph.models.getAppropiateGroupId
import nl.hicts.mph.models.getAppropiateVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ScanProjectUtilTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `should discover pom only when max depth reaches it`() {
        writePom("level-one/level-two/pom.xml", "org.example", "deep-project", "1.0.0")

        assertEquals(emptyList<Any>(), ScanProjectUtil.searchAllMavenProjects(tempDir.toFile(), 2))

        val projects = ScanProjectUtil.searchAllMavenProjects(tempDir.toFile(), 3)
        assertEquals(listOf("deep-project"), projects.map { it.model.artifactId })
    }

    @Test
    fun `should read a pom passed directly as a file`() {
        val pom = writePom("pom.xml", "org.example", "direct-project", "1.0.0")

        val projects = ScanProjectUtil.searchAllMavenProjects(pom.toFile(), 1)

        assertEquals(1, projects.size)
        assertEquals("org.example:direct-project", projects.single().artifact())
        assertEquals(pom.toAbsolutePath().normalize().toString(), projects.single().pomLocation.toPath().toAbsolutePath().normalize().toString())
    }

    @Test
    fun `should ignore invalid xml and non xml files`() {
        Files.writeString(tempDir.resolve("invalid.xml"), "<not-a-pom>")
        Files.writeString(tempDir.resolve("pom.txt"), "not xml")

        assertEquals(emptyList<Any>(), ScanProjectUtil.searchAllMavenProjects(tempDir.toFile(), 1))
    }

    @Test
    fun `should load declared modules and preserve their parent relationship`() {
        Files.createDirectories(tempDir.resolve("module-a"))
        Files.writeString(tempDir.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example</groupId><artifactId>root</artifactId><version>1.0.0</version>
              <packaging>pom</packaging><modules><module>module-a</module></modules>
            </project>
        """.trimIndent())
        writePom("module-a/pom.xml", null, "module-a", null)

        val root = ScanProjectUtil.searchAllMavenProjects(tempDir.toFile(), 1).single()
        val module = root.modules.single()

        assertEquals("root", root.model.artifactId)
        assertEquals("module-a", module.model.artifactId)
        assertEquals("org.example", module.getAppropiateGroupId().value)
        assertEquals("1.0.0", module.getAppropiateVersion().value)
        assertEquals(root.artifact(), module.moduleWithinProject?.artifact())
    }

    @Test
    fun `should return projects in deterministic path order`() {
        writePom("z-project/pom.xml", "org.example", "z-project", "1.0")
        writePom("a-project/pom.xml", "org.example", "a-project", "1.0")

        val projects = ScanProjectUtil.searchAllMavenProjects(tempDir.toFile(), 2)

        assertEquals(listOf("a-project", "z-project"), projects.map { it.model.artifactId })
    }

    private fun writePom(relativePath: String, groupId: String?, artifactId: String, version: String?): Path {
        val path = tempDir.resolve(relativePath)
        Files.createDirectories(path.parent)
        val group = groupId?.let { "<groupId>$it</groupId>" }.orEmpty()
        val projectVersion = version?.let { "<version>$it</version>" }.orEmpty()
        Files.writeString(path, """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              $group<artifactId>$artifactId</artifactId>$projectVersion
            </project>
        """.trimIndent())
        return path
    }
}
