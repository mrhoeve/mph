package nl.hicts.mph.intellij.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.services.*
import org.junit.Assert.assertThrows

class GitRebaseDialogTest : BasePlatformTestCase() {
    private val old = MavenProjectInfo("org.example", "old-module", "PREFIX-1", "C:/workspace/repository/old/pom.xml", "C:/workspace/repository")
    private val fresh = old.copy(artifactId = "renamed-module", pomPath = "C:/workspace/repository/new/pom.xml", version = "2")
    private val dependent = old.copy(artifactId = "dependent", gitRootPath = "C:/workspace/dependent", pomPath = "C:/workspace/dependent/pom.xml")
    private val plan = GitRebasePlan("PREFIX-", listOf(GitRepositoryPlan(old.gitRootPath!!, "repository")), listOf(old))

    fun testRefreshCompletesBeforeDiscoveryAndAlignmentAndKeepsExclusiveOwnership() {
        lateinit var complete: () -> Unit
        var discovered = false
        var aligned = false
        val dialog = GitRebaseDialog(project, plan,
            reloadMaven = { success, _ -> complete = success },
            discover = { discovered = true; listOf(fresh, dependent) },
            align = { selected, workspace ->
                assertEquals(listOf(fresh), selected)
                assertEquals(listOf(fresh, dependent), workspace)
                aligned = true
            })
        try {
            dialog.refreshAndAlign()
            assertFalse(discovered)
            assertBusy()
            complete()
            assertTrue(aligned)
            WorkspaceOperationCoordinator.run("Build") { }
            complete() // Duplicate callbacks must not edit the workspace again.
        } finally {
            complete()
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun testClosingDuringRefreshSkipsAlignmentAndReleasesOwnershipOnlyAfterRefresh() {
        lateinit var complete: () -> Unit
        val dialog = GitRebaseDialog(project, plan,
            reloadMaven = { success, _ -> complete = success },
            discover = { error("Discovery must not run after Close") },
            align = { _, _ -> error("Alignment must not run after Close") })
        dialog.refreshAndAlign()
        try {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
            assertBusy()
        } finally {
            complete()
        }
        WorkspaceOperationCoordinator.run("Build") { }
    }

    fun testRefreshFailureSkipsAlignmentAndReleasesOwnership() {
        val dialog = GitRebaseDialog(project, plan,
            reloadMaven = { _, failure -> failure(IllegalStateException("Test refresh failure")) },
            discover = { error("Discovery must not run after failure") })
        try {
            dialog.refreshAndAlign()
            assertTrue(dialog.statusText.contains("Test refresh failure"))
            WorkspaceOperationCoordinator.run("Build") { }
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun testMissingRefreshedRepositoryPreventsAlignment() {
        val dialog = GitRebaseDialog(project, plan,
            reloadMaven = { success, _ -> success() }, discover = { listOf(dependent) },
            align = { _, _ -> error("Incomplete discovery must not be aligned") })
        try {
            dialog.refreshAndAlign()
            assertTrue(dialog.statusText.contains("every synchronized repository"))
            WorkspaceOperationCoordinator.run("Build") { }
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }
    private fun assertBusy() {
        assertThrows(IllegalStateException::class.java) { WorkspaceOperationCoordinator.acquire("Build") }
    }

}
