package nl.hicts.mph.intellij.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.services.MavenBuildOptions

class MavenBuildDialogTest : BasePlatformTestCase() {
    fun testFailureIsNotReportedAsSuccessWhenTheFinishedCallbackRuns() {
        withDialog { dialog ->
            val task = dialog.createBuildTask(MavenBuildOptions())
            task.onThrowable(IllegalStateException("Test process could not start"))
            task.onFinished()

            assertEquals("Build failed: Test process could not start", dialog.statusText)
        }
    }

    fun testCancellationBeforeResultsAreAvailableIsNotReportedAsSuccess() {
        withDialog { dialog ->
            val task = dialog.createBuildTask(MavenBuildOptions())
            task.onCancel()
            task.onFinished()

            assertEquals("Build cancelled", dialog.statusText)
        }
    }

    private fun withDialog(action: (MavenBuildDialog) -> Unit) {
        val dialog = MavenBuildDialog(project, listOf(MavenProjectInfo(
            groupId = "org.example",
            artifactId = "sample-service",
            version = "1.0-SNAPSHOT",
            pomPath = "/workspace/sample-service/pom.xml",
            gitRootPath = null,
        )))
        try {
            action(dialog)
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }
}
