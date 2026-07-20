package nl.hicts.mph.intellij.services

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import nl.hicts.mph.intellij.model.MavenProjectInfo
import java.nio.file.Files
import java.nio.file.Path

class BulkVersionUpdateServiceTest : BasePlatformTestCase() {
    fun testUpdatesSelectedVersionsAndDependentReferencesAsOneUndoableCommand() {
        val targetSource = pom("shared-api", "1.0-SNAPSHOT")
        val dependentSource = pom(
            "sample-service",
            "1.0-SNAPSHOT",
            """
                <dependencies>
                    <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>shared-api</artifactId>
                        <version>1.0-SNAPSHOT</version>
                    </dependency>
                </dependencies>
            """.trimIndent(),
        )
        val targetFile = tempPom("target", targetSource)
        val dependentFile = tempPom("dependent", dependentSource)
        val target = projectInfo("shared-api", targetFile)
        val dependent = projectInfo("sample-service", dependentFile)

        try {
            val result = project.service<BulkVersionUpdateService>().update(
                BulkVersionUpdateRequest(
                    selectedProjects = listOf(target),
                    workspaceProjects = listOf(target, dependent),
                    prefix = "PREFIX-1234-",
                    mode = BulkVersionMode.ADD_PREFIX,
                    updateDependents = true,
                ),
            )
            val targetDocument = document(targetFile)
            val dependentDocument = document(dependentFile)

            assertEquals(1, result.updatedProjectCount)
            assertEquals(1, result.updatedReferenceCount)
            assertTrue(targetDocument.text.contains("<version>PREFIX-1234-1.0-SNAPSHOT</version>"))
            assertTrue(dependentDocument.text.contains("<version>PREFIX-1234-1.0-SNAPSHOT</version>"))

            val undoManager = UndoManager.getInstance(project)
            assertTrue(undoManager.isUndoAvailable(null))
            undoManager.undo(null)
            assertEquals(targetSource, targetDocument.text)
            assertEquals(dependentSource, dependentDocument.text)
        } finally {
            Files.deleteIfExists(targetFile)
            Files.deleteIfExists(dependentFile)
        }
    }

    private fun tempPom(name: String, content: String): Path =
        Files.createTempFile("mph-bulk-$name-", ".txt").also { Files.writeString(it, content) }

    private fun document(path: Path) = FileDocumentManager.getInstance().getDocument(
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!,
    )!!

    private fun projectInfo(artifactId: String, path: Path) = MavenProjectInfo(
        groupId = "org.example",
        artifactId = artifactId,
        version = "1.0-SNAPSHOT",
        pomPath = path.toString(),
        gitRootPath = path.parent.toString(),
    )

    private fun pom(artifactId: String, version: String, body: String = "") = """
        <project>
            <modelVersion>4.0.0</modelVersion>
            <groupId>org.example</groupId>
            <artifactId>$artifactId</artifactId>
            <version>$version</version>
            $body
        </project>
    """.trimIndent()
}
