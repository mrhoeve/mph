package nl.hicts.mph.services

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import nl.hicts.mph.models.Settings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ManagedPropertiesTest {

    @TempDir
    lateinit var tempDir: Path

    private val mavenCommandService = mockk<MavenCommandService>()
    private val gitService = mockk<GitService>()
    private val nexusIqService = mockk<NexusIqService>()
    private val settingsService = mockk<SettingsService>()
    private val sbomService = mockk<SbomService>()
    private val service = MavenProjectService(
        mavenCommandService,
        gitService,
        nexusIqService,
        settingsService,
        sbomService
    )

    @Test
    fun `should load version properties from module parent and nested imported boms`() {
        writePom("pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example</groupId>
              <artifactId>test-root</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
              <modules>
                <module>base-bom</module>
                <module>platform-bom</module>
                <module>parent</module>
                <module>app-module</module>
              </modules>
            </project>
        """)
        writePom("base-bom/pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example</groupId>
              <artifactId>base-bom</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
              <properties><nested-library.version>2.1.0</nested-library.version></properties>
            </project>
        """)
        writePom("platform-bom/pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example</groupId>
              <artifactId>platform-bom</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
              <properties><platform-library.version>3.2.0</platform-library.version></properties>
              <dependencyManagement><dependencies><dependency>
                <groupId>org.example</groupId><artifactId>base-bom</artifactId><version>1.0.0</version><type>pom</type><scope>import</scope>
              </dependency></dependencies></dependencyManagement>
            </project>
        """)
        writePom("parent/pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example</groupId>
              <artifactId>test-parent</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
              <properties><parent-library.version>4.3.0</parent-library.version></properties>
              <dependencyManagement><dependencies><dependency>
                <groupId>org.example</groupId><artifactId>platform-bom</artifactId><version>1.0.0</version><type>pom</type><scope>import</scope>
              </dependency></dependencies></dependencyManagement>
            </project>
        """)
        writePom("app-module/pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>org.example</groupId><artifactId>test-parent</artifactId><version>1.0.0</version>
                <relativePath>../parent/pom.xml</relativePath>
              </parent>
              <artifactId>app-module</artifactId>
              <properties><local-library.version>5.4.0</local-library.version></properties>
            </project>
        """)

        every { settingsService.loadSettings() } returns Settings(basePath = tempDir, maxScanDepth = 4)
        every { nexusIqService.extractNexusIqAppId(any(), any()) } returns null
        justRun { gitService.clearCache() }
        justRun { sbomService.setWorkspace(any()) }

        val properties = service.getManagedProperties(tempDir, 4, tempDir.resolve("app-module/pom.xml").toString())
        val byName = properties.associateBy { it.name }

        assertEquals("5.4.0", byName["local-library.version"]?.value)
        assertEquals("Local POM", byName["local-library.version"]?.source)
        assertEquals("4.3.0", byName["parent-library.version"]?.value)
        assertEquals("test-parent", byName["parent-library.version"]?.source)
        assertEquals("3.2.0", byName["platform-library.version"]?.value)
        assertEquals("platform-bom", byName["platform-library.version"]?.source)
        assertEquals("2.1.0", byName["nested-library.version"]?.value)
        assertEquals("base-bom", byName["nested-library.version"]?.source)
        assertTrue(properties.none { it.source == "Spring Boot" }, "Non-Spring properties must not be labeled as Spring Boot")
    }

    private fun writePom(relativePath: String, content: String) {
        val path = tempDir.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content.trimIndent())
    }
}
