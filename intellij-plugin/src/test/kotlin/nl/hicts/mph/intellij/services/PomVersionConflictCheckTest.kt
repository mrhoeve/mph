package nl.hicts.mph.intellij.services

import org.junit.Assert.*
import org.junit.Test

class PomVersionConflictCheckTest {
    private val hunk = "<<<<<<< HEAD\n<version>2.0</version>\n=======\n<version>FEATURE-1.0</version>\n>>>>>>> feature\n"
    @Test fun `accepts real Maven version text but refuses comments CDATA and configuration`() {
        assertEquals("<project>\n<version>2.0</version>\n</project>",
            GitVersionConflictResolver.resolvedPomContent("<project>\n$hunk</project>"))
        listOf("<!--\n" to "-->", "<description><![CDATA[\n" to "]]></description>",
            "<build><plugins><plugin><configuration>\n" to "</configuration></plugin></plugins></build>",
            "<build><plugins><plugin><configuration><dependency>\n" to "</dependency></configuration></plugin></plugins></build>").forEach { (start, end) ->
            assertNull(GitVersionConflictResolver.resolvedPomContent("<project>\n$start$hunk$end\n</project>"))
        }
        assertFalse(PomVersionConflictCheck.onlyVersionTextChanges("<project><version>2</version></project>",
            "<project><version>1</version><modules/></project>"))
    }
    @Test fun `project version edits ignore XML examples and inherit only from the matching parent`() {
        val parent = "<project><!-- <version>99</version> --><version>2.0</version></project>"
        assertEquals("2.0", PomReferenceVersionEditor.findProjectVersion(parent))
        assertTrue(PomReferenceVersionEditor.updateProjectVersion(parent, "FEATURE-2.0").content.contains("<!-- <version>99</version> -->"))
        val coordinates = nl.hicts.mph.intellij.model.MavenCoordinates("org.example", "parent")
        val child = "<project><parent><groupId>org.example</groupId><artifactId>parent</artifactId><version>2.0</version></parent></project>"
        assertTrue(PomReferenceVersionEditor.inheritsVersionFrom(child, parent, coordinates))
        assertFalse(PomReferenceVersionEditor.inheritsVersionFrom(child.replace("parent</artifactId>", "external</artifactId>"), parent, coordinates))
    }
}
