package nl.hicts.mph.intellij.ui

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import nl.hicts.mph.intellij.model.GitProjectGroup
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.model.ProjectSnapshot
import java.awt.event.MouseEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class MphToolWindowPanelTest : BasePlatformTestCase() {
    fun testRendersRepositoriesAndProjects() {
        val snapshot = snapshot(
            MavenProjectInfo(
                groupId = "org.example",
                artifactId = "sample-service",
                version = "1.2-SNAPSHOT",
                pomPath = "/workspace/sample-service/pom.xml",
                gitRootPath = "/workspace/sample-service",
            ),
        )
        val panel = MphToolWindowPanel(project, refreshOnCreate = false)

        panel.render(snapshot)

        assertEquals("1 Maven projects in 1 Git repositories", panel.summaryText)
        val root = panel.projectTree.model.root as DefaultMutableTreeNode
        val repository = root.getChildAt(0) as DefaultMutableTreeNode
        val module = repository.getChildAt(0) as DefaultMutableTreeNode
        assertEquals("sample-service", repository.toString())
        assertEquals("sample-service  1.2-SNAPSHOT  (org.example)", module.toString())
    }

    fun testRefreshTaskRendersDiscoveryResultAndReportsErrors() {
        val snapshot = snapshot(projectInfo("sample-service", "/workspace/sample-service/pom.xml"))
        val panel = MphToolWindowPanel(project, { snapshot }, refreshOnCreate = false)
        val task = panel.createRefreshTask()

        task.run(EmptyProgressIndicator())
        task.onSuccess()
        assertEquals("1 Maven projects in 1 Git repositories", panel.summaryText)

        task.onThrowable(IllegalStateException("Test discovery failure"))
        assertEquals("Unable to discover Maven projects: Test discovery failure", panel.summaryText)
    }

    fun testOpensTheSelectedPom() {
        val pomPath = "/workspace/sample-service/pom.xml"
        var openedPomPath: String? = null
        val panel = MphToolWindowPanel(
            project,
            openPom = { openedPomPath = it },
            refreshOnCreate = false,
        )
        panel.render(snapshot(projectInfo("sample-service", pomPath)))
        val root = panel.projectTree.model.root as DefaultMutableTreeNode
        val repository = root.getChildAt(0) as DefaultMutableTreeNode
        val module = repository.getChildAt(0) as DefaultMutableTreeNode
        panel.projectTree.selectionPath = TreePath(module.path)

        panel.openSelectedPom()

        assertEquals(pomPath, openedPomPath)
    }

    fun testFormatsProjectEntriesWithoutOptionalCoordinates() {
        val projectInfo = MavenProjectInfo(null, "sample-service", null, "/pom.xml", null)

        assertEquals("sample-service", ProjectTreeEntry(projectInfo).toString())
        assertEquals("sample-service", projectInfo.coordinates)
    }

    fun testHandlesOptionalValuesAndSelectionGuards() {
        val projectInfo = MavenProjectInfo("", "sample-service", "", "/pom.xml", null)
        val panel = MphToolWindowPanel(project, refreshOnCreate = false)
        panel.render(snapshot(projectInfo))

        assertEquals("sample-service", ProjectTreeEntry(projectInfo).toString())
        val root = panel.projectTree.model.root as DefaultMutableTreeNode
        val outsideGit = root.getChildAt(0) as DefaultMutableTreeNode
        assertEquals("Outside a Git repository", outsideGit.toString())

        panel.projectTree.clearSelection()
        panel.openSelectedPom()
        panel.projectTree.selectionPath = TreePath(outsideGit.path)
        panel.openSelectedPom()
    }

    fun testDetectsUnixAndWindowsPomChangesOnly() {
        val panel = MphToolWindowPanel(project, refreshOnCreate = false)

        assertTrue(panel.containsPomChange(listOf("/workspace/service/pom.xml")))
        assertTrue(panel.containsPomChange(listOf("C:\\workspace\\service\\pom.xml")))
        assertFalse(panel.containsPomChange(listOf("/workspace/service/README.md")))
        assertFalse(panel.containsPomChange(emptyList()))
    }

    fun testUsesDefaultDiscoveryAndErrorNameFallback() {
        val panel = MphToolWindowPanel(project, refreshOnCreate = false)
        val task = panel.createRefreshTask()

        task.run(EmptyProgressIndicator())
        task.onSuccess()
        task.onThrowable(IllegalStateException())

        assertEquals("Unable to discover Maven projects: IllegalStateException", panel.summaryText)
    }

    fun testDefaultPomNavigationIgnoresMissingFiles() {
        val panel = MphToolWindowPanel(project, refreshOnCreate = false)

        selectOnlyProject(panel, projectInfo("missing-service", "/missing/pom.xml"))
        panel.openSelectedPom()
    }

    fun testDoubleLeftClickOpensOnlyTheSelectedProject() {
        var openedPomPath: String? = null
        val panel = MphToolWindowPanel(
            project,
            openPom = { openedPomPath = it },
            refreshOnCreate = false,
        )
        val projectInfo = projectInfo("sample-service", "/workspace/sample-service/pom.xml")
        selectOnlyProject(panel, projectInfo)
        val listener = panel.projectTree.mouseListeners.last()

        listener.mouseClicked(mouseEvent(panel, clickCount = 1, button = MouseEvent.BUTTON1))
        assertNull(openedPomPath)
        listener.mouseClicked(mouseEvent(panel, clickCount = 2, button = MouseEvent.BUTTON3))
        assertNull(openedPomPath)
        listener.mouseClicked(mouseEvent(panel, clickCount = 2, button = MouseEvent.BUTTON1))
        assertEquals(projectInfo.pomPath, openedPomPath)
    }

    private fun selectOnlyProject(panel: MphToolWindowPanel, projectInfo: MavenProjectInfo) {
        panel.render(snapshot(projectInfo))
        val root = panel.projectTree.model.root as DefaultMutableTreeNode
        val repository = root.getChildAt(0) as DefaultMutableTreeNode
        val module = repository.getChildAt(0) as DefaultMutableTreeNode
        panel.projectTree.selectionPath = TreePath(module.path)
    }

    private fun mouseEvent(panel: MphToolWindowPanel, clickCount: Int, button: Int) = MouseEvent(
        panel.projectTree,
        MouseEvent.MOUSE_CLICKED,
        0,
        0,
        0,
        0,
        clickCount,
        false,
        button,
    )

    private fun snapshot(projectInfo: MavenProjectInfo) = ProjectSnapshot(
        listOf(GitProjectGroup(projectInfo.gitRootPath, listOf(projectInfo))),
    )

    private fun projectInfo(artifactId: String, pomPath: String) = MavenProjectInfo(
        groupId = "org.example",
        artifactId = artifactId,
        version = "1.0-SNAPSHOT",
        pomPath = pomPath,
        gitRootPath = "/workspace/$artifactId",
    )
}
