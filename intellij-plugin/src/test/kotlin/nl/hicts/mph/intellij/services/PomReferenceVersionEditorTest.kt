package nl.hicts.mph.intellij.services

import nl.hicts.mph.intellij.model.MavenCoordinates
import nl.hicts.mph.intellij.model.MavenReferenceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PomReferenceVersionEditorTest {
    private val target = MavenCoordinates("org.example", "shared-api")

    @Test
    fun `updates parent direct and managed dependency versions only`() {
        val pom = pom(
            projectVersion = "1.0-SNAPSHOT",
            body = """
                <parent>
                    <groupId>org.example</groupId><artifactId>shared-api</artifactId><version>1.0</version>
                </parent>
                <!-- <dependency><groupId>org.example</groupId><artifactId>shared-api</artifactId><version>comment</version></dependency> -->
                <dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>shared-api</artifactId><version>1.0</version></dependency>
                </dependencies>
                <dependencyManagement><dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>shared-api</artifactId><version>1.0</version></dependency>
                </dependencies></dependencyManagement>
                <build><plugins><plugin><dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>shared-api</artifactId><version>plugin-version</version></dependency>
                </dependencies></plugin></plugins></build>
            """.trimIndent(),
        )

        val result = PomReferenceVersionEditor.update(pom, target, "2.0-SNAPSHOT", MavenReferenceKind.entries.toSet())

        assertTrue(result.changed)
        assertEquals(3, result.updatedReferenceCount)
        assertEquals(3, Regex("<version>2.0-SNAPSHOT</version>").findAll(result.content).count())
        assertTrue(result.content.contains("<version>plugin-version</version>"))
        assertTrue(result.content.contains("<version>comment</version>"))
    }

    @Test
    fun `updates a local version property once for every matching reference`() {
        val pom = pom(
            body = """
                <properties><shared-api.version>1.0</shared-api.version></properties>
                <dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>shared-api</artifactId><version>${'$'}{shared-api.version}</version></dependency>
                </dependencies>
                <dependencyManagement><dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>shared-api</artifactId><version>${'$'}{shared-api.version}</version></dependency>
                </dependencies></dependencyManagement>
            """.trimIndent(),
        )

        val result = PomReferenceVersionEditor.update(
            pom,
            target,
            "2.0",
            setOf(MavenReferenceKind.DEPENDENCY, MavenReferenceKind.MANAGED_DEPENDENCY),
        )

        assertEquals(2, result.updatedReferenceCount)
        assertTrue(result.content.contains("<shared-api.version>2.0</shared-api.version>"))
        assertTrue(result.unresolvedProperties.isEmpty())
    }

    @Test
    fun `reports inherited properties and references without a local version`() {
        val pom = pom(
            body = """
                <dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>shared-api</artifactId><version>${'$'}{inherited.version}</version></dependency>
                    <dependency><groupId>org.example</groupId><artifactId>shared-api</artifactId></dependency>
                </dependencies>
            """.trimIndent(),
        )

        val result = PomReferenceVersionEditor.update(
            pom,
            target,
            "2.0",
            setOf(MavenReferenceKind.DEPENDENCY),
        )

        assertFalse(result.changed)
        assertEquals(setOf("inherited.version"), result.unresolvedProperties)
        assertEquals(1, result.missingVersionCount)
    }

    @Test
    fun `finds literal and property based project versions`() {
        assertEquals("2.0", PomReferenceVersionEditor.findProjectVersion(pom(projectVersion = "2.0")))
        assertEquals(
            "2.1-SNAPSHOT",
            PomReferenceVersionEditor.findProjectVersion(
                pom(
                    projectVersion = "${'$'}{revision}",
                    body = "<properties><revision>2.1-SNAPSHOT</revision></properties>",
                ),
            ),
        )
        assertEquals(null, PomReferenceVersionEditor.findProjectVersion(pom(projectVersion = null)))
    }

    @Test
    fun `rejects unsafe target versions`() {
        assertThrows(IllegalArgumentException::class.java) {
            PomReferenceVersionEditor.update(pom(), target, "2<&", setOf(MavenReferenceKind.DEPENDENCY))
        }
    }

    private fun pom(projectVersion: String? = null, body: String = ""): String = """
        <project>
            <modelVersion>4.0.0</modelVersion>
            <groupId>org.example</groupId>
            <artifactId>sample-service</artifactId>
            ${projectVersion?.let { "<version>$it</version>" }.orEmpty()}
            $body
        </project>
    """.trimIndent()
}
