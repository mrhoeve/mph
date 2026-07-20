package nl.hicts.mph.intellij.actions

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import nl.hicts.mph.intellij.model.DependentProjectsAnalysis
import nl.hicts.mph.intellij.model.MavenProjectInfo

class FindDependentProjectsTaskTest : BasePlatformTestCase() {
    fun testShowsTheCompletedAnalysis() {
        val analysis = analysis()
        var shown: DependentProjectsAnalysis? = null
        var missing = false
        val task = FindDependentProjectsTask(
            project,
            findDependents = { analysis },
            showAnalysis = { shown = it },
            showMissingProject = { missing = true },
            showError = { fail(it.message) },
        )

        task.run(EmptyProgressIndicator())
        task.onSuccess()

        assertSame(analysis, shown)
        assertFalse(missing)
    }

    fun testReportsAnUnlinkedPom() {
        var missing = false
        val task = FindDependentProjectsTask(
            project,
            findDependents = { null },
            showAnalysis = { fail("No analysis should be shown") },
            showMissingProject = { missing = true },
            showError = { fail(it.message) },
        )

        task.run(EmptyProgressIndicator())
        task.onSuccess()

        assertTrue(missing)
    }

    fun testForwardsDiscoveryErrors() {
        val failure = IllegalStateException("Test discovery failure")
        var shownFailure: Throwable? = null
        val task = FindDependentProjectsTask(
            project,
            findDependents = { null },
            showAnalysis = { fail("No analysis should be shown") },
            showMissingProject = { fail("Missing should not be shown") },
            showError = { shownFailure = it },
        )

        task.onThrowable(failure)

        assertSame(failure, shownFailure)
    }

    private fun analysis() = DependentProjectsAnalysis(
        MavenProjectInfo(
            groupId = "org.example",
            artifactId = "shared-api",
            version = "1.0-SNAPSHOT",
            pomPath = "/workspace/shared-api/pom.xml",
            gitRootPath = "/workspace/shared-api",
        ),
        emptyList(),
    )
}
