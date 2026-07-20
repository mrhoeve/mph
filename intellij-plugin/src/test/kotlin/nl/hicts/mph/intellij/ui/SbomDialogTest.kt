package nl.hicts.mph.intellij.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.services.SbomAnalysis
import nl.hicts.mph.intellij.services.SbomComponent

class SbomDialogTest : BasePlatformTestCase() {
    fun testExpandsHiddenRootForPopulatedSbom() {
        val dialog = SbomDialog(
            project,
            SbomAnalysis(
                MavenProjectInfo("org.example", "sample-service", "1.0.0", "/workspace/pom.xml", "/workspace"),
                listOf(
                    SbomComponent(
                        "org.example",
                        "sample-library",
                        "2.0.0",
                        "jar",
                        "compile",
                        true,
                        emptyList(),
                    ),
                ),
            ),
        )

        try {
            assertEquals(1, dialog.dependencyTree.rowCount)
            assertEquals("", dialog.dependencyTree.emptyText.text)
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }
}
