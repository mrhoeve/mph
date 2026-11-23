package nl.hicts.mph.intellij.services

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files

class MavenModelRefreshPlatformTest : BasePlatformTestCase() {
    fun testReportsUnlinkedPomThroughPlatformCallback() {
        val pom = Files.createTempFile("mph-unlinked-", "-pom.xml")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var failure: Throwable? = null
        try {
            Files.writeString(pom, "<project/>")
            val file = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(pom))
            val service = MavenModelRefreshService(project, scope)
            service.reloadChangedPoms(setOf(file.path),
                { fail("An unlinked POM must not be synchronized") },
                { failure = it },
            )
            assertNull(failure)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertEquals("POM is no longer linked as a Maven project: ${file.path}", failure?.message)
        } finally {
            scope.cancel()
            Files.deleteIfExists(pom)
        }
    }
}
