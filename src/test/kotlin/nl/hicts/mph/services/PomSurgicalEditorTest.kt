package nl.hicts.mph.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class PomSurgicalEditorTest {

    @Test
    fun `should remove only one property when multiple overrides exist`() {
        val pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>test</artifactId>
                <version>1.0.0</version>
                <properties>
                    <caffeine.version>3.4.0</caffeine.version>
                    <mongodb.version>5.8.0</mongodb.version>
                </properties>
            </project>
        """.trimIndent()

        val tempFile = Files.createTempFile("pom", ".xml").toFile()
        tempFile.writeText(pomContent)

        PomSurgicalEditor.edit(tempFile) {
            removeProperty("caffeine.version")
        }

        val updatedContent = tempFile.readText()
        assertTrue(updatedContent.contains("<mongodb.version>5.8.0</mongodb.version>"), "mongodb.version should still be present")
        assertTrue(!updatedContent.contains("<caffeine.version>3.4.0</caffeine.version>"), "caffeine.version should be removed")
        
        PomSurgicalEditor.edit(tempFile) {
            removeProperty("mongodb.version")
        }
        
        val finalContent = tempFile.readText()
        assertTrue(!finalContent.contains("<mongodb.version>5.8.0</mongodb.version>"), "mongodb.version should be removed")
        
        tempFile.delete()
    }

    @Test
    fun `should handle properties with comments when removing`() {
        val pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <properties>
                    <p0>v0</p0>
                    <!-- overridden for CVE -->
                    <caffeine.version>3.4.0</caffeine.version>
                    <!-- important comment to keep -->
                    <!-- another override -->
                    <mongodb.version>5.8.0</mongodb.version>
                </properties>
            </project>
        """.trimIndent()

        val tempFile = Files.createTempFile("pom", ".xml").toFile()
        tempFile.writeText(pomContent)

        PomSurgicalEditor.edit(tempFile) {
            removeProperty("caffeine.version")
        }

        val updatedContent = tempFile.readText()
        assertTrue(updatedContent.contains("<p0>v0</p0>"), "p0 should still be present")
        assertTrue(updatedContent.contains("<mongodb.version>5.8.0</mongodb.version>"), "mongodb.version should still be present")
        assertTrue(updatedContent.contains("<!-- another override -->"), "mongodb.version comment should still be present")
        assertTrue(updatedContent.contains("<!-- important comment to keep -->"), "important comment should be preserved")
        assertTrue(!updatedContent.contains("<caffeine.version>3.4.0</caffeine.version>"), "caffeine.version should be removed")
        assertTrue(!updatedContent.contains("<!-- overridden for CVE -->"), "caffeine.version comment should be removed")
        
        PomSurgicalEditor.edit(tempFile) {
            removeProperty("mongodb.version")
        }
        
        val finalContent = tempFile.readText()
        assertTrue(finalContent.contains("<!-- important comment to keep -->"), "important comment should still be preserved")
        assertTrue(!finalContent.contains("<!-- another override -->"), "mongodb override comment should be removed")
        
        tempFile.delete()
    }

    @Test
    fun `should handle property names with regex special characters`() {
        val pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <properties>
                    <mongodb-version>1.0.0</mongodb-version>
                    <mongodb.version>5.8.0</mongodb.version>
                </properties>
            </project>
        """.trimIndent()

        val tempFile = Files.createTempFile("pom", ".xml").toFile()
        tempFile.writeText(pomContent)

        // If not escaped, mongodb.version might match mongodb-version
        PomSurgicalEditor.edit(tempFile) {
            removeProperty("mongodb.version")
        }

        val updatedContent = tempFile.readText()
        // If bug exists, mongodb-version might be removed instead of mongodb.version
        assertTrue(updatedContent.contains("<mongodb-version>1.0.0</mongodb-version>"), "mongodb-version should still be present")
        assertTrue(!updatedContent.contains("<mongodb.version>5.8.0</mongodb.version>"), "mongodb.version should be removed")
        
        tempFile.delete()
    }

    @Test
    fun `should not update dependency version when project version is missing`() {
        val pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                </parent>
                <artifactId>child</artifactId>
                <dependencies>
                    <dependency>
                        <groupId>org.jetbrains.kotlin</groupId>
                        <artifactId>kotlin-stdlib-jdk8</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()

        val tempFile = Files.createTempFile("pom", ".xml").toFile()
        tempFile.writeText(pomContent)

        PomSurgicalEditor.edit(tempFile) {
            updateProjectVersion("2.0.0")
        }

        val updatedContent = tempFile.readText()
        // If bug exists, it will update kotlin-stdlib-jdk8 version to 2.0.0
        assertTrue(updatedContent.contains("<version>1.0.0</version>"), "Dependency version should not be updated. Content: \n${'$'}updatedContent")
        assertTrue(!updatedContent.contains("<version>2.0.0</version>"), "Should not contain new version in dependency. Content: \n${'$'}updatedContent")
        
        tempFile.delete()
    }

    @Test
    fun `should only update property in properties block`() {
        val pomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <properties>
                    <foo.version>1.0.0</foo.version>
                </properties>
                <build>
                    <plugins>
                        <plugin>
                            <configuration>
                                <foo.version>bar</foo.version>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
            </project>
        """.trimIndent()

        val tempFile = Files.createTempFile("pom", ".xml").toFile()
        tempFile.writeText(pomContent)

        PomSurgicalEditor.edit(tempFile) {
            updateProperty("foo.version", "2.0.0")
        }

        val updatedContent = tempFile.readText()
        assertTrue(updatedContent.contains("<foo.version>2.0.0</foo.version>"), "Property in properties block should be updated")
        assertTrue(updatedContent.contains("<foo.version>bar</foo.version>"), "Tag in configuration block should NOT be updated")
        
        tempFile.delete()
    }
}
