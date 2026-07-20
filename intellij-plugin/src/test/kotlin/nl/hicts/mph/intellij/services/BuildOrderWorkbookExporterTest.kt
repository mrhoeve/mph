package nl.hicts.mph.intellij.services

import nl.hicts.mph.intellij.model.BuildOrderEntry
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.model.WorkspaceBuildOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class BuildOrderWorkbookExporterTest {
    @Test
    fun `exports a valid minimal xlsx workbook`() {
        val order = WorkspaceBuildOrder(
            listOf(
                BuildOrderEntry(project("shared-api", "1.0-SNAPSHOT"), 1, emptyList()),
                BuildOrderEntry(project("sample-service", "2.0-SNAPSHOT"), 2, listOf("shared-api")),
            ),
        )

        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(BuildOrderWorkbookExporter.export(order))).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                entry = zip.nextEntry
            }
        }

        assertEquals(
            setOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/worksheets/sheet1.xml",
            ),
            entries.keys,
        )
        assertTrue(entries.getValue("xl/worksheets/sheet1.xml").contains("sample-service"))
        assertTrue(entries.getValue("xl/worksheets/sheet1.xml").contains("shared-api"))
    }

    private fun project(artifactId: String, version: String) = MavenProjectInfo(
        groupId = "org.example",
        artifactId = artifactId,
        version = version,
        pomPath = "C:/workspace/$artifactId/pom.xml",
        gitRootPath = "C:/workspace/$artifactId",
    )
}
