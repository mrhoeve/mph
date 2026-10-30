package nl.hicts.mph.intellij.services

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import nl.hicts.mph.intellij.model.DependentMavenProject
import nl.hicts.mph.intellij.model.DependentProjectsAnalysis
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.model.MavenReferenceKind
import java.nio.file.Files

class DependentProjectVersionUpdateServiceTest : BasePlatformTestCase() {
    fun testUpdatesAndUndoesAllDocumentChanges() {
        val originalPom = """
            <project>
                <dependencies>
                    <dependency>
                        <groupId>org.example</groupId>
                        <artifactId>shared-api</artifactId>
                        <version>1.0</version>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent()
        // A .txt suffix keeps unrelated bundled XML language servers out of this focused VFS test.
        val physicalPom = Files.createTempFile("mph-dependent-pom-", ".txt")
        Files.writeString(physicalPom, originalPom)
        val dependentPom = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(physicalPom)!!
        val analysis = DependentProjectsAnalysis(
            target = projectInfo("shared-api", "2.0", "/workspace/shared-api/pom.xml"),
            dependents = listOf(
                DependentMavenProject(
                    projectInfo("sample-service", "1.0", dependentPom.path),
                    setOf(MavenReferenceKind.DEPENDENCY),
                ),
            ),
        )

        try {
            val result = project.service<DependentProjectVersionUpdateService>().update(analysis)
            val document = FileDocumentManager.getInstance().getDocument(dependentPom)!!

            assertEquals(1, result.updatedProjectCount)
            assertEquals(1, result.updatedReferenceCount)
            assertTrue(document.text.contains("<version>2.0</version>"))
            val undoManager = UndoManager.getInstance(project)
            assertTrue(undoManager.isUndoAvailable(null))

            undoManager.undo(null)

            assertEquals(originalPom, document.text)
        } finally {
            Files.deleteIfExists(physicalPom)
        }
    }

    private fun projectInfo(artifactId: String, version: String, pomPath: String) = MavenProjectInfo(
        groupId = "org.example",
        artifactId = artifactId,
        version = version,
        pomPath = pomPath,
        gitRootPath = null,
    )
}
