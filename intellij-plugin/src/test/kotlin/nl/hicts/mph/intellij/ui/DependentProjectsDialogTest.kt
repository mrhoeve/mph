package nl.hicts.mph.intellij.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import nl.hicts.mph.intellij.model.DependentMavenProject
import nl.hicts.mph.intellij.model.DependentProjectsAnalysis
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.model.MavenReferenceKind
import com.intellij.ui.components.JBList

class DependentProjectsDialogTest : BasePlatformTestCase() {
    fun testCreatesAProjectPreview() {
        val analysis = DependentProjectsAnalysis(
            target = projectInfo("shared-api", "2.0-SNAPSHOT"),
            dependents = listOf(
                DependentMavenProject(
                    projectInfo("sample-service", "1.0-SNAPSHOT"),
                    setOf(MavenReferenceKind.DEPENDENCY, MavenReferenceKind.MANAGED_DEPENDENCY),
                ),
            ),
        )
        val dialog = DependentProjectsDialog(project, analysis)

        try {
            assertEquals("Update Dependent Maven Projects", dialog.title)
            assertTrue(dialog.canApplyUpdate)
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun testCreatesAnEmptyPreviewForAProjectWithoutDependents() {
        val dialog = DependentProjectsDialog(
            project,
            DependentProjectsAnalysis(projectInfo("standalone", null), emptyList()),
        )

        try {
            assertEquals("Update Dependent Maven Projects", dialog.title)
            assertFalse(dialog.canApplyUpdate)
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun testRendersDependentDetailsWithAndWithoutAVersion() {
        val withVersion = DependentMavenProject(
            projectInfo("sample-service", "1.0-SNAPSHOT"),
            setOf(MavenReferenceKind.DEPENDENCY),
        )
        val withoutVersion = DependentMavenProject(
            projectInfo("sample-client", null),
            setOf(MavenReferenceKind.PARENT),
        )
        val list = JBList(listOf(withVersion, withoutVersion))
        val renderer = DependentProjectListRenderer()

        renderer.getListCellRendererComponent(list, withVersion, 0, false, false)
        assertEquals("/workspace/sample-service/pom.xml", renderer.toolTipText)

        renderer.getListCellRendererComponent(list, withoutVersion, 1, true, true)
        assertEquals("/workspace/sample-client/pom.xml", renderer.toolTipText)
    }

    private fun projectInfo(artifactId: String, version: String?) = MavenProjectInfo(
        groupId = "org.example",
        artifactId = artifactId,
        version = version,
        pomPath = "/workspace/$artifactId/pom.xml",
        gitRootPath = "/workspace/$artifactId",
    )
}
