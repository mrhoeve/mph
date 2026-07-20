package nl.hicts.mph.intellij.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitWorkspaceServiceTest {
    @Test
    fun `parses ahead and behind counts`() {
        assertEquals(3 to 7, GitOutputParser.aheadBehind("3\t7\n"))
        assertNull(GitOutputParser.aheadBehind("unavailable"))
    }

    @Test
    fun `validates branch names before invoking Git`() {
        assertTrue(GitOutputParser.validBranchName("feature/spring-boot-4"))
        assertFalse(GitOutputParser.validBranchName("feature branch"))
        assertFalse(GitOutputParser.validBranchName("../main"))
        assertFalse(GitOutputParser.validBranchName("topic.lock."))
    }
}
