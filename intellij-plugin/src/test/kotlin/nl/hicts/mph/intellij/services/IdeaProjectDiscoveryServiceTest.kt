package nl.hicts.mph.intellij.services

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class IdeaProjectDiscoveryServiceTest : BasePlatformTestCase() {
    fun testDiscoversAnEmptyIdeaProject() {
        val snapshot = project.service<IdeaProjectDiscoveryService>().discover()

        assertEquals(0, snapshot.projectCount)
        assertEquals(0, snapshot.repositoryCount)
        assertEmpty(snapshot.groups)
    }

    fun testDoesNotFindDependentsForAnUnlinkedPom() {
        assertNull(project.service<IdeaProjectDiscoveryService>().findDependents("/missing/pom.xml", null))
    }
}
