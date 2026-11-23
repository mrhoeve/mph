package nl.hicts.mph.intellij.services

import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedVersionPropertiesTest {
    @Test
    fun `saved override is visible while the Maven model still has the old value`() {
        val poms = listOf(
            "<project></project>",
            "<project><properties><!-- Upgrade --><library.version>1.0</library.version></properties></project>",
        )
        for (pom in poms) {
            val updated = PomReferenceVersionEditor.upsertProperty(pom, "library.version", "2.0", "Upgrade").content
            val properties = managedVersionProperties(updated, mapOf("library.version" to "1.0"))

            assertEquals(
                listOf(ManagedVersionProperty("library.version", "2.0", "Local POM", true, "Upgrade")),
                ManagedPropertyFilter.filter(properties, overridesOnly = true, query = "2.0"),
            )
        }
    }

    @Test
    fun `properties without local overrides still use the imported value`() {
        assertEquals(
            listOf(ManagedVersionProperty("library.version", "1.0", "Inherited Maven model", false)),
            managedVersionProperties("<project/>", mapOf("library.version" to "1.0", "encoding" to "UTF-8")),
        )
    }
}
