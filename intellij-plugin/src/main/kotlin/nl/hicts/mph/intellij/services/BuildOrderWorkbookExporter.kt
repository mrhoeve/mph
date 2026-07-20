package nl.hicts.mph.intellij.services

import nl.hicts.mph.intellij.model.WorkspaceBuildOrder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BuildOrderWorkbookExporter {
    fun export(order: WorkspaceBuildOrder): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.textEntry(
                "[Content_Types].xml",
                """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                      <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    </Types>
                """.trimIndent(),
            )
            zip.textEntry(
                "_rels/.rels",
                """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>
                """.trimIndent(),
            )
            zip.textEntry(
                "xl/workbook.xml",
                """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets><sheet name="Build Order" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>
                """.trimIndent(),
            )
            zip.textEntry(
                "xl/_rels/workbook.xml.rels",
                """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    </Relationships>
                """.trimIndent(),
            )
            zip.textEntry("xl/worksheets/sheet1.xml", worksheet(order))
        }
        return output.toByteArray()
    }

    private fun worksheet(order: WorkspaceBuildOrder): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        append("<cols><col min=\"1\" max=\"1\" width=\"12\" customWidth=\"1\"/>")
        append("<col min=\"2\" max=\"2\" width=\"35\" customWidth=\"1\"/>")
        append("<col min=\"3\" max=\"3\" width=\"24\" customWidth=\"1\"/>")
        append("<col min=\"4\" max=\"4\" width=\"55\" customWidth=\"1\"/></cols><sheetData>")
        append(row(1, listOf("Build Step", "Project Name", "Current Version", "Depends On")))
        order.entries.forEachIndexed { index, entry ->
            val rowNumber = index + 2
            append("<row r=\"$rowNumber\">")
            append("<c r=\"A$rowNumber\"><v>${entry.buildStep}</v></c>")
            append(inlineCell("B$rowNumber", entry.project.artifactId))
            append(inlineCell("C$rowNumber", entry.project.version.orEmpty()))
            append(inlineCell("D$rowNumber", entry.dependsOn.joinToString(", ")))
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    private fun row(number: Int, values: List<String>): String = buildString {
        append("<row r=\"$number\">")
        values.forEachIndexed { index, value -> append(inlineCell("${('A'.code + index).toChar()}$number", value)) }
        append("</row>")
    }

    private fun inlineCell(reference: String, value: String): String =
        "<c r=\"$reference\" t=\"inlineStr\"><is><t>${xml(value)}</t></is></c>"

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun ZipOutputStream.textEntry(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
