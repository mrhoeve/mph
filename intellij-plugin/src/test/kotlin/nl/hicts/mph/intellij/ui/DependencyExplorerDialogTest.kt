package nl.hicts.mph.intellij.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import nl.hicts.mph.intellij.model.MavenCoordinates
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.model.MavenReferenceKind
import nl.hicts.mph.intellij.model.WorkspaceProjectRelationship
import nl.hicts.mph.intellij.model.WorkspaceProjectRelationships
import javax.swing.tree.DefaultMutableTreeNode

class DependencyExplorerDialogTest : BasePlatformTestCase() {
    fun testPopulatesAndExpandsRelationshipGroups() {
        val selected = projectInfo("sample-service")
        val dependency = projectInfo("sample-api")
        val relationships = WorkspaceProjectRelationships(
            selected,
            listOf(WorkspaceProjectRelationship(
                MavenCoordinates("org.example", "sample-api"),
                dependency,
                setOf(MavenReferenceKind.DEPENDENCY),
            )),
            emptyList(),
        )

        val dialog = DependencyExplorerDialog(project, relationships) {}
        val root = dialog.dependencyTree.model.root as DefaultMutableTreeNode
        val dependencies = root.getChildAt(0) as DefaultMutableTreeNode

        assertEquals(2, root.childCount)
        assertEquals(1, dependencies.childCount)
    }

    private fun projectInfo(artifactId: String) = MavenProjectInfo(
        "org.example",
        artifactId,
        "1.0-SNAPSHOT",
        "/workspace/$artifactId/pom.xml",
        "/workspace/$artifactId",
    )
}
