package nl.hicts.mph.intellij.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.services.*
import java.awt.Container
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JButton

/** Offscreen layout checks; these are not a substitute for native IDE interaction. */
class PluginDialogVisualTest : BasePlatformTestCase() {
    fun testAlignmentIncludesAllPomsByDefaultAndAllowsIndividualExclusions() {
        val first = MavenProjectInfo("org.example", "first", "1.0", "/workspace/first/pom.xml", null)
        val second = first.copy(artifactId = "second", pomPath = "/workspace/second/pom.xml")
        val edits = listOf(first, second).map { versionEdit(it) }
        val plan = VersionAlignmentPlan(emptyList(), edits, BulkVersionUpdateResult(2, 0, 0, emptyList()))
        val dialog = VersionAlignmentPreviewDialog(project, plan)
        try {
            assertEquals(edits, dialog.selectedPlan().edits)
            dialog.files.setValueAt(false, 2, 0)
            assertEquals(listOf(edits.first()), dialog.selectedPlan().edits)
            dialog.files.setValueAt(false, 0, 0)
            assertTrue(dialog.selectedPlan().edits.isEmpty())
            dialog.files.setValueAt(true, 3, 0)
            assertEquals(listOf(edits.last()), dialog.selectedPlan().edits)
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    fun testAlignmentDiffUpdatesWhenAChangeIsDeselected() {
        val info = MavenProjectInfo("org.example", "example", "1.0", "/workspace/example/pom.xml", null)
        val source = "<project><version>1.0</version><properties><library.version>2.0</library.version></properties></project>"
        val projectOffset = source.indexOf("1.0")
        val propertyOffset = source.indexOf("2.0")
        val edit = AlignmentEdit(info, source, listOf(
            PomVersionChange(projectOffset until projectOffset + 3, "Project version", "1.0", "FEATURE-1.0"),
            PomVersionChange(propertyOffset until propertyOffset + 3, "Property: library.version", "2.0", "2.1", referenceCount = 3),
        ), selectedProject = true)
        val dialog = VersionAlignmentPreviewDialog(project, VersionAlignmentPlan(
            emptyList(), listOf(edit), BulkVersionUpdateResult(1, 3, 0, emptyList()),
        ))
        try {
            assertEquals(edit.after, dialog.previewContent(0))
            dialog.files.setValueAt(false, 1, 0)
            assertEquals(source.replace("2.0", "2.1"), dialog.previewContent(0))
            assertEquals(3, dialog.selectedPlan().result.updatedReferenceCount)
            assertEquals(dialog.previewContent(0), dialog.selectedPlan().edits.single().after)
            dialog.files.setValueAt(false, 0, 0)
            assertEquals(source, dialog.previewContent(0))
            assertTrue(dialog.selectedPlan().edits.isEmpty())
            dialog.files.setValueAt(true, 0, 0)
            assertEquals(edit.after, dialog.previewContent(0))
        } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        }
    }

    private fun versionEdit(info: MavenProjectInfo): AlignmentEdit {
        val source = "<project><version>1.0</version></project>"
        val start = source.indexOf("1.0")
        return AlignmentEdit(info, source, listOf(
            PomVersionChange(start until start + 3, "Project version", "1.0", "FEATURE-1.0"),
        ), selectedProject = true)
    }

    fun testDialogsRenderAtNormalAndNarrowWidths() {
        val info = MavenProjectInfo("org.example", "sample-library", "FEATURE-1.0-SNAPSHOT", "C:/workspace/library/pom.xml", "C:/workspace/library")
        val plan = VersionAlignmentPlan(emptyList(), listOf(versionEdit(info)), BulkVersionUpdateResult(1, 0, 0, emptyList()))
        val dialogs = listOf(
            "alignment" to VersionAlignmentPreviewDialog(project, plan),
            "recovery" to RecoveryBrowserDialog(project, emptyList()),
            "build" to MavenBuildDialog(project, listOf(info)),
            "synchronize" to GitRebaseDialog(project, GitRebasePlan("FEATURE-", listOf(GitRepositoryPlan(info.gitRootPath!!, info.artifactId)), listOf(info))),
        )
        try {
            for ((kind, dialog) in dialogs) for (width in listOf(640, 1000)) {
                val center = DialogWrapper::class.java.getDeclaredMethod("createCenterPanel").apply { isAccessible = true }.invoke(dialog) as javax.swing.JComponent
                @Suppress("UNCHECKED_CAST")
                val actions = DialogWrapper::class.java.getDeclaredMethod("createActions").apply { isAccessible = true }.invoke(dialog) as Array<javax.swing.Action>
                val content = javax.swing.JPanel(java.awt.BorderLayout(0, 8)).apply {
                    add(center, java.awt.BorderLayout.CENTER)
                    add(javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT)).apply {
                        actions.forEach { add(JButton(it)) }
                    }, java.awt.BorderLayout.SOUTH)
                }
                content.setSize(width, 620)
                layoutTree(content)
                // IntelliJ's diff divider requires an on-screen window. Check its layout
                // here, but leave painting the actual diff editor to the live IDE checklist.
                if (kind != "alignment") {
                    val image = BufferedImage(width, 620, BufferedImage.TYPE_INT_ARGB)
                    val graphics = image.createGraphics()
                    try { content.paint(graphics) } finally { graphics.dispose() }
                    val directory = Files.createDirectories(Path.of("build/reports/dialog-visuals"))
                    ImageIO.write(image, "png", directory.resolve("$kind-$width.png").toFile())
                }
                assertTrue("$kind has no visible controls", buttons(content).any { it.width > 0 && it.height > 0 })
                for (button in buttons(content).filter { it.isVisible && it.width > 0 && !it.text.isNullOrBlank() }) {
                    assertTrue("$kind: clipped button '${button.text}' at $width", button.width >= button.preferredSize.width)
                }
            }
        } finally { dialogs.forEach { it.second.close(DialogWrapper.CANCEL_EXIT_CODE) } }
    }
    private fun layoutTree(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layoutTree)
    }
    private fun buttons(container: Container): List<JButton> = container.components.flatMap {
        (if (it is JButton) listOf(it) else emptyList()) + (if (it is Container) buttons(it) else emptyList())
    }
}
