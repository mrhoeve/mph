package nl.hicts.mph.intellij.ui

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import nl.hicts.mph.intellij.model.GitProjectGroup
import nl.hicts.mph.intellij.model.GitWorkspaceStatus
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.model.ProjectSnapshot
import nl.hicts.mph.intellij.services.BulkVersionUpdateResult
import java.awt.event.MouseEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath
import javax.swing.JTree

class MphToolWindowPanelTest : BasePlatformTestCase() {
    fun testVersionUpdateRefreshesOnlyAfterTheChangedPomsAreSynced() {
        val original = projectInfo("sample-service", "/workspace/sample-service/pom.xml")
        val updated = original.copy(version = "FEATURE-1.0-SNAPSHOT")
        var discoverCount = 0
        var onSyncSuccess: (() -> Unit)? = null
        val changed = setOf(original.pomPath, "/workspace/dependent/pom.xml")
        val panel = MphToolWindowPanel(
            project,
            discoverProjects = { snapshot(if (++discoverCount == 1) original else updated) },
            queueRefreshTask = { task -> task.run(EmptyProgressIndicator()); task.onSuccess() },
            refreshOnCreate = false,
            syncChangedPoms = { paths, onSuccess, _ ->
                assertEquals(changed, paths)
                onSyncSuccess = onSuccess
            },
        )
        panel.render(snapshot(original))
        val stale = panel.createRefreshTask().also { it.run(EmptyProgressIndicator()) }
        panel.syncAndRefreshAfterVersionUpdate(BulkVersionUpdateResult(1, 1, 0, emptyList(), changed))
        panel.refresh()
        stale.onSuccess()
        assertEquals("Synchronizing changed Maven projects…", panel.summaryText)
        assertEquals(1, discoverCount)

        onSyncSuccess?.invoke() ?: fail("Targeted Maven sync was not requested")
        val root = panel.projectTree.model.root as DefaultMutableTreeNode
        val repo = (root.getChildAt(0) as DefaultMutableTreeNode).userObject as RepositoryTreeEntry
        assertEquals(updated.version, repo.rootProject()?.version)
        assertEquals(2, discoverCount)
    }

    fun testReportsSyncFailureWithoutClaimingThePomEditsFailed() {
        val panel = MphToolWindowPanel(
            project,
            queueRefreshTask = { fail("Do not refresh stale versions after sync failure") },
            refreshOnCreate = false,
            syncChangedPoms = { _, _, onFailure -> onFailure(IllegalStateException("Test sync failure")) },
        )
        panel.syncAndRefreshAfterVersionUpdate(BulkVersionUpdateResult(0, 0, 1, emptyList()))
        assertEquals("Discovering Maven projects…", panel.summaryText)
        panel.syncAndRefreshAfterVersionUpdate(
            BulkVersionUpdateResult(1, 0, 0, emptyList(), setOf("/workspace/example/pom.xml")),
        )
        assertEquals("POM changes saved, but Maven sync failed: Test sync failure", panel.summaryText)
    }

    fun testBranchFilterUsesCurrentRepositoryBranchesAndDefaultsToTheMajority() {
        val panel = MphToolWindowPanel(project, refreshOnCreate = false)
        val longBranch = "feature/" + "long-branch-name-".repeat(20)
        panel.render(branchSnapshot("feature/x", "feature/x", longBranch))
        val root = panel.projectTree.model.root as DefaultMutableTreeNode

        assertTrue(panel.branchFilters.isVisible)
        assertEquals(RepositoryBranchFilter.ALL, panel.branchFilterMode.selectedItem)
        assertEquals("feature/x", panel.branchChoice.selectedItem)
        assertFalse(panel.branchChoice.isEnabled)
        assertEquals(listOf(longBranch, "feature/x"), (0 until panel.branchChoice.itemCount).map(panel.branchChoice::getItemAt))
        assertEquals(3, root.childCount)

        panel.projectTree.setSelectionRow(0)
        panel.branchFilterMode.selectedItem = RepositoryBranchFilter.DIFFERENT
        assertTrue(panel.branchChoice.isEnabled)
        assertTrue(panel.selectedProjects().isEmpty())
        assertEquals("1 of 3 Git repositories", panel.summaryText)
        val different = root.getChildAt(0) as DefaultMutableTreeNode
        assertEquals("repo-2", different.toString())
        assertEquals(2, different.childCount)

        panel.branchChoice.selectedItem = longBranch
        panel.branchFilterMode.selectedItem = RepositoryBranchFilter.MATCHING
        assertEquals(longBranch, panel.branchChoice.toolTipText)
        assertEquals("repo-2", root.getChildAt(0).toString())
        assertEquals(1, root.childCount)

        panel.render(branchSnapshot("feature/x", "feature/x", "feature/x"))
        assertFalse(panel.branchFilters.isVisible)
        assertEquals(RepositoryBranchFilter.ALL, panel.branchFilterMode.selectedItem)
        assertEquals(3, root.childCount)
    }

    fun testNoMajorityLeavesBranchUnsetAndBranchlessRepositoriesCanBeFound() {
        val panel = MphToolWindowPanel(project, refreshOnCreate = false)
        panel.render(branchSnapshot("develop", "feature/x", null))
        val root = panel.projectTree.model.root as DefaultMutableTreeNode
        assertNull(panel.branchChoice.selectedItem)
        assertEquals(2, panel.branchChoice.itemCount)
        panel.branchFilterMode.selectedItem = RepositoryBranchFilter.DIFFERENT
        assertEquals(3, root.childCount)
        panel.branchChoice.selectedItem = "feature/x"
        assertEquals(listOf("repo-0", "repo-2"), (0 until root.childCount).map { root.getChildAt(it).toString() })
    }

    private fun branchSnapshot(vararg branches: String?) = ProjectSnapshot(branches.mapIndexed { index, branch ->
        val name = "repo-$index"
        val root = "/workspace/$name"
        val main = projectInfo(name, "$root/pom.xml").copy(gitStatus = branch?.let { GitWorkspaceStatus(it, 0, 0) })
        GitProjectGroup(root, listOf(main, main.copy(artifactId = "$name-module", pomPath = "$root/module/pom.xml")))
    })

    fun testToolbarAndContextMenuGroupActionsAndExposeVersionAndRecoveryCommands() {
        val panel = MphToolWindowPanel(project, refreshOnCreate = false)
        val toolbar = panel.toolbarActions.getChildren(null)
        assertEquals(4, toolbar.count { it is com.intellij.openapi.actionSystem.Separator })
        val context = panel.contextActions.getChildren(null).mapNotNull { it.templatePresentation.text }
        for (name in listOf("Open POM", "Dependencies", "Update Version", "Align Versions", "Realign Versions", "Build", "Sync with develop", "Recovery Copies")) {
            assertTrue(name, name in context)
        }
        assertNotNull(panel.projectTree.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).get(javax.swing.KeyStroke.getKeyStroke("shift F10")))
    }

    fun testContextMenuPreservesExistingMultipleSelection() {
        val panel = MphToolWindowPanel(project, refreshOnCreate = false)
        panel.render(ProjectSnapshot(listOf(GitProjectGroup("/workspace", listOf(
            projectInfo("first", "/workspace/first/pom.xml"), projectInfo("second", "/workspace/second/pom.xml"),
        )))))
        val root = panel.projectTree.model.root as DefaultMutableTreeNode
        val repository = root.getChildAt(0) as DefaultMutableTreeNode
        val first = TreePath((repository.getChildAt(0) as DefaultMutableTreeNode).path)
        val second = TreePath((repository.getChildAt(1) as DefaultMutableTreeNode).path)
        panel.projectTree.selectionPaths = arrayOf(first, second)
        panel.selectContextTarget(first)
        assertEquals(2, panel.projectTree.selectionCount)
        panel.selectContextTarget(TreePath(repository.path))
        assertEquals(1, panel.projectTree.selectionCount)
    }

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
        assertEquals("sample-service", module.toString())
    }

    fun testStartsCollapsedAndPreservesExpansionStateAcrossRefreshes() {
        val projectInfo = projectInfo("sample-service", "/workspace/sample-service/pom.xml")
        val panel = MphToolWindowPanel(project, refreshOnCreate = false)
        panel.render(snapshot(projectInfo))
        val root = panel.projectTree.model.root as DefaultMutableTreeNode
        val repository = root.getChildAt(0) as DefaultMutableTreeNode
        val repositoryPath = TreePath(repository.path)
        assertFalse(panel.projectTree.isExpanded(repositoryPath))

        panel.expandAllRows()
        assertTrue(panel.projectTree.isExpanded(repositoryPath))
        panel.render(snapshot(projectInfo))

        val refreshedRoot = panel.projectTree.model.root as DefaultMutableTreeNode
        val refreshedRepository = refreshedRoot.getChildAt(0) as DefaultMutableTreeNode
        val refreshedRepositoryPath = TreePath(refreshedRepository.path)
        assertTrue(panel.projectTree.isExpanded(refreshedRepositoryPath))

        panel.collapseAllRows()
        assertFalse(panel.projectTree.isExpanded(refreshedRepositoryPath))
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

    fun testReloadsTheMavenModelBeforeRefreshingTheScreen() {
        var reloadRequested = false
        var discovered = false
        val refreshed = snapshot(projectInfo("refreshed-service", "/workspace/refreshed-service/pom.xml"))
        val panel = MphToolWindowPanel(
            project = project,
            discoverProjects = {
                discovered = true
                refreshed
            },
            reloadMavenProjects = { onSuccess, _ ->
                reloadRequested = true
                assertFalse(discovered)
                onSuccess()
            },
            queueRefreshTask = { task ->
                task.run(EmptyProgressIndicator())
                task.onSuccess()
            },
            refreshOnCreate = false,
        )

        panel.reloadAndRefresh()
        assertTrue(reloadRequested)
        assertEquals("Reloading Maven projects…", panel.summaryText)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(discovered)
        assertEquals("1 Maven projects in 1 Git repositories", panel.summaryText)
    }

    fun testReportsMavenReloadFailures() {
        val panel = MphToolWindowPanel(
            project = project,
            reloadMavenProjects = { _, onFailure -> onFailure(IllegalStateException("Test reload failure")) },
            refreshOnCreate = false,
        )

        panel.reloadAndRefresh()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals("Unable to reload Maven projects: Test reload failure", panel.summaryText)
    }

    fun testReloadsAndRediscoversMavenProjectsBeforeRealigningVersions() {
        val pomPath = "/workspace/sample-service/pom.xml"
        val original = projectInfo("sample-service", pomPath).copy(version = "1.0-SNAPSHOT")
        val refreshed = original.copy(version = "2.0-SNAPSHOT")
        var reloadRequested = false
        var rediscovered = false
        var alignedProjects = emptyList<MavenProjectInfo>()
        var alignedWorkspace = emptyList<MavenProjectInfo>()
        val panel = MphToolWindowPanel(
            project = project,
            discoverProjects = {
                assertTrue(reloadRequested)
                rediscovered = true
                snapshot(refreshed)
            },
            reloadMavenProjects = { onSuccess, _ ->
                reloadRequested = true
                assertFalse(rediscovered)
                onSuccess()
            },
            queueRefreshTask = { task ->
                task.run(EmptyProgressIndicator())
                task.onSuccess()
            },
            realignVersions = { selected, workspace ->
                assertTrue(rediscovered)
                alignedProjects = selected
                alignedWorkspace = workspace
                BulkVersionUpdateResult(0, 0, selected.size, emptyList())
            },
            versionResultNotifier = { _, _ -> },
            refreshOnCreate = false,
        )
        selectOnlyProject(panel, original)

        panel.realignSelectedVersions()
        assertTrue(reloadRequested)
        assertTrue(alignedProjects.isEmpty())
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertEquals(listOf(refreshed), alignedProjects)
        assertEquals(listOf(refreshed), alignedWorkspace)
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

    fun testOpensTheRootPomFromARepositoryRow() {
        val rootPom = "/workspace/sample/pom.xml"
        var openedPomPath: String? = null
        val rootProject = projectInfo("sample-parent", rootPom).copy(gitRootPath = "/workspace/sample")
        val module = projectInfo("sample-module", "/workspace/sample/module/pom.xml")
            .copy(gitRootPath = "/workspace/sample")
        val panel = MphToolWindowPanel(
            project,
            openPom = { openedPomPath = it },
            refreshOnCreate = false,
        )
        panel.render(ProjectSnapshot(listOf(GitProjectGroup("/workspace/sample", listOf(rootProject, module)))))
        val treeRoot = panel.projectTree.model.root as DefaultMutableTreeNode
        val repository = treeRoot.getChildAt(0) as DefaultMutableTreeNode
        panel.projectTree.selectionPath = TreePath(repository.path)

        panel.openSelectedPom()

        assertEquals(rootPom, openedPomPath)
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

    fun testRendersRepositoryProjectAndFallbackTreeRows() {
        val renderer = MphProjectTreeRenderer()
        val tree = JTree()
        val repository = RepositoryTreeEntry(
            "sample-repository",
            "/workspace/sample",
            listOf(
                projectInfo("sample-service", "/workspace/sample/pom.xml"),
                projectInfo("sample-client", "/workspace/sample/client/pom.xml"),
            ),
        )
        val projectWithCoordinates = ProjectTreeEntry(
            MavenProjectInfo(
                "org.example",
                "sample-service",
                "1.0-SNAPSHOT",
                "/workspace/sample/pom.xml",
                "/workspace/sample",
            ),
        )
        val projectWithoutCoordinates = ProjectTreeEntry(
            MavenProjectInfo(null, "sample-client", null, "/workspace/client/pom.xml", null),
        )

        renderer.getTreeCellRendererComponent(tree, DefaultMutableTreeNode(repository), false, true, false, 0, false)
        assertEquals("sample-repository  1.0-SNAPSHOT", renderer.getCharSequence(false).toString())
        assertNull(renderer.toolTipText)

        renderer.getTreeCellRendererComponent(tree, DefaultMutableTreeNode(projectWithCoordinates), true, false, true, 1, true)
        assertEquals("sample-service", renderer.getCharSequence(false).toString())
        assertNull(renderer.toolTipText)

        renderer.getTreeCellRendererComponent(tree, DefaultMutableTreeNode(projectWithoutCoordinates), false, false, true, 2, false)
        assertEquals("sample-client", renderer.getCharSequence(false).toString())
        assertNull(renderer.toolTipText)

        renderer.getTreeCellRendererComponent(tree, DefaultMutableTreeNode("Other"), false, false, true, 3, false)
    }

    fun testSelectsIndividualProjectsAndCompleteRepositoriesWithoutDuplicates() {
        val first = projectInfo("sample-service", "/workspace/sample/pom.xml")
            .copy(gitRootPath = "/workspace/sample")
        val second = projectInfo("sample-client", "/workspace/sample/client/pom.xml")
            .copy(gitRootPath = "/workspace/sample")
        val panel = MphToolWindowPanel(project, refreshOnCreate = false)
        panel.render(ProjectSnapshot(listOf(GitProjectGroup("/workspace/sample", listOf(first, second)))))
        val root = panel.projectTree.model.root as DefaultMutableTreeNode
        val repository = root.getChildAt(0) as DefaultMutableTreeNode
        val firstProject = repository.getChildAt(0) as DefaultMutableTreeNode

        panel.projectTree.selectionPaths = arrayOf(TreePath(repository.path), TreePath(firstProject.path))

        assertEquals(listOf(first, second), panel.selectedProjects())
        assertEquals(listOf(first), panel.selectedBuildProjects())
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
