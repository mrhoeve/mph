package nl.hicts.mph.intellij.actions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDependentProjectsActionTest {
    @Test
    fun testRecognizesPomFilesOnly() {
        val action = UpdateDependentProjectsAction()

        assertTrue(action.isPomFileName("pom.xml", isDirectory = false))
        assertTrue(action.isPomFileName("POM.XML", isDirectory = false))
        assertFalse(action.isPomFileName("README.md", isDirectory = false))
        assertFalse(action.isPomFileName("pom.xml", isDirectory = true))
        assertFalse(action.isPomFileName(null, isDirectory = false))
    }
}
