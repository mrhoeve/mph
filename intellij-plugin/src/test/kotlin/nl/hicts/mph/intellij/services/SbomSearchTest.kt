package nl.hicts.mph.intellij.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SbomSearchTest {
    private val transitive = SbomComponent(
        "com.fasterxml.jackson.core", "jackson-databind", "2.21.1", "jar", "runtime", true, emptyList(),
    )
    private val direct = SbomComponent(
        "org.example", "sample-client", "1.0.0", "jar", "compile", true, listOf(transitive),
    )

    @Test
    fun `matches coordinates version and scope case insensitively`() {
        assertEquals("jackson-databind", SbomSearch.filter(listOf(direct), "JACKSON").single().children.single().artifactId)
        assertEquals("jackson-databind", SbomSearch.filter(listOf(direct), "2.21.1").single().children.single().artifactId)
        assertEquals("sample-client", SbomSearch.filter(listOf(direct), "compile").single().artifactId)
        assertEquals(
            "jackson-databind",
            SbomSearch.filter(listOf(direct), "com.fasterxml.jackson.core:jackson").single().children.single().artifactId,
        )
    }

    @Test
    fun `preserves parent path to matching transitive dependency`() {
        val result = SbomSearch.filter(listOf(direct), "jackson-databind")

        assertEquals("sample-client", result.single().artifactId)
        assertEquals(listOf(transitive), result.single().children)
    }

    @Test
    fun `returns full tree for blank query and nothing for unknown dependency`() {
        assertEquals(listOf(direct), SbomSearch.filter(listOf(direct), "  "))
        assertTrue(SbomSearch.filter(listOf(direct), "not-present").isEmpty())
    }
}
