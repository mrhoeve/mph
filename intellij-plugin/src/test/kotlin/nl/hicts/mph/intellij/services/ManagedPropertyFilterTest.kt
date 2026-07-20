package nl.hicts.mph.intellij.services

import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedPropertyFilterTest {
    private val properties = listOf(
        ManagedVersionProperty("spring-cloud.version", "2025.1.2", "Inherited Maven model", false),
        ManagedVersionProperty("kotlin.version", "2.3.20", "Local POM", true),
    )

    @Test
    fun `show only overrides excludes inherited properties`() {
        assertEquals(
            listOf("kotlin.version"),
            ManagedPropertyFilter.filter(properties, overridesOnly = true, query = "").map { it.name },
        )
    }

    @Test
    fun `search matches property names and values`() {
        assertEquals(
            listOf("spring-cloud.version"),
            ManagedPropertyFilter.filter(properties, overridesOnly = false, query = "2025.1").map { it.name },
        )
    }
}
