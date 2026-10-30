package nl.hicts.mph.intellij.services

import nl.hicts.mph.intellij.model.MavenCoordinates
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Properties

class PomDependencyDeclarationsTest {
    @Test
    fun `reads only declared project and managed dependencies and resolves properties`() {
        val pom = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <dependencies>
                <dependency><groupId>org.example</groupId><artifactId>sample-api</artifactId></dependency>
              </dependencies>
              <dependencyManagement><dependencies>
                <dependency><groupId>${'$'}{platform.group}</groupId><artifactId>sample-bom</artifactId></dependency>
              </dependencies></dependencyManagement>
              <build><plugins><plugin><dependencies>
                <dependency><groupId>org.plugin</groupId><artifactId>plugin-library</artifactId></dependency>
              </dependencies></plugin></plugins></build>
            </project>
        """.trimIndent()
        val declarations = PomDependencyDeclarations.parse(
            pom,
            Properties().apply { setProperty("platform.group", "org.platform") },
        )

        assertEquals(setOf(MavenCoordinates("org.example", "sample-api")), declarations.dependencies)
        assertEquals(setOf(MavenCoordinates("org.platform", "sample-bom")), declarations.managedDependencies)
    }
}
