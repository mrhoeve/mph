package nl.hicts.mph.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MavenModelResolverTest {

    @TempDir
    lateinit var localRepository: Path

    @Test
    fun `should use a locally cached pom regardless of its remote repository origin`() {
        val artifactDirectory = localRepository.resolve("org/example/example-bom/1.2.3")
        Files.createDirectories(artifactDirectory)
        Files.writeString(
            artifactDirectory.resolve("example-bom-1.2.3.pom"),
            """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>example-bom</artifactId>
                  <version>1.2.3</version>
                  <packaging>pom</packaging>
                  <properties>
                    <example-library.version>4.5.6</example-library.version>
                  </properties>
                </project>
            """.trimIndent()
        )
        Files.writeString(
            artifactDirectory.resolve("_remote.repositories"),
            "example-bom-1.2.3.pom>company-nexus=\n"
        )

        val model = MavenModelResolver(localRepositoryPath = localRepository.toFile())
            .resolveModel("org.example", "example-bom", "1.2.3")

        assertEquals("example-bom", model.artifactId)
        assertEquals("4.5.6", model.properties.getProperty("example-library.version"))
    }
}
