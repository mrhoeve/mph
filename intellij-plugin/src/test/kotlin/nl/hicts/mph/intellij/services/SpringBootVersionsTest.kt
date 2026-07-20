package nl.hicts.mph.intellij.services

import org.junit.Assert.assertEquals
import org.junit.Test

class SpringBootVersionsTest {
    @Test
    fun `returns only stable newer versions in descending order`() {
        assertEquals(
            listOf("4.1.1", "4.1.0", "3.5.9"),
            SpringBootVersions.stableNewerThan(
                "3.5.8",
                listOf("v4.1.0", "4.1.1", "4.1.0-RC1", "3.5.9", "3.5.8", "not-a-version"),
            ),
        )
    }
}
