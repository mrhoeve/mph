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
    fun testDialogsRenderAtNormalAndNarrowWidths() {
        val info = MavenProjectInfo("org.example", "sample-library", "FEATURE-1.0-SNAPSHOT", "C:/workspace/library/pom.xml", "C:/workspace/library")
        val plan = VersionAlignmentPlan(emptyList(), listOf(AlignmentEdit(info, "<project><version>1.0</version></project>", "<project><version>FEATURE-1.0</version></project>")), BulkVersionUpdateResult(1, 0, 0, emptyList()))
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
