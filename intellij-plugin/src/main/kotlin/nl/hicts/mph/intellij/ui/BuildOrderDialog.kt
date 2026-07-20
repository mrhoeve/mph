package nl.hicts.mph.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import nl.hicts.mph.intellij.model.BuildOrderEntry
import nl.hicts.mph.intellij.model.WorkspaceBuildOrder
import nl.hicts.mph.intellij.services.BuildOrderWorkbookExporter
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

class BuildOrderDialog(
    private val ideProject: Project,
    private val order: WorkspaceBuildOrder,
    private val openPom: (String) -> Unit,
) : DialogWrapper(ideProject) {
    private val tableModel = object : DefaultTableModel(
        arrayOf("Step", "Project", "Version", "Depends on"),
        0,
    ) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JBTable(tableModel)

    init {
        title = "Maven Workspace Build Order"
        order.entries.forEach { entry ->
            tableModel.addRow(
                arrayOf<Any>(
                    if (entry.partOfCycle) "${entry.buildStep} ⚠" else entry.buildStep,
                    entry.project.artifactId,
                    entry.project.version.orEmpty(),
                    entry.dependsOn.joinToString(", "),
                ),
            )
        }
        table.setShowGrid(false)
        table.rowHeight = JBUI.scale(28)
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(65)
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(220)
        table.columnModel.getColumn(2).preferredWidth = JBUI.scale(160)
        table.columnModel.getColumn(3).preferredWidth = JBUI.scale(330)
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && table.selectedRow >= 0) {
                    order.entries.getOrNull(table.convertRowIndexToModel(table.selectedRow))
                        ?.project?.pomPath?.let(openPom)
                }
            }
        })
        init()
    }

    override fun createCenterPanel(): JComponent {
        val heading = JBLabel("Build order", AllIcons.Actions.Compile, JBLabel.LEFT)
        heading.font = heading.font.deriveFont(Font.BOLD, heading.font.size2D + 3f)
        val summary = JBLabel(
            if (order.hasCycles) {
                "A dependency cycle was detected. Cyclic repositories are grouped in the final step."
            } else {
                "${order.entries.size} repositories · ${order.entries.maxOfOrNull(BuildOrderEntry::buildStep) ?: 0} build steps"
            },
        )
        summary.foreground = if (order.hasCycles) JBUI.CurrentTheme.Validator.warningBackgroundColor() else
            JBUI.CurrentTheme.ContextHelp.FOREGROUND
        val header = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(heading, BorderLayout.NORTH)
            add(summary, BorderLayout.SOUTH)
        }
        val buildButton = JButton("Build All", AllIcons.Actions.Execute).apply {
            isEnabled = order.entries.isNotEmpty() && !order.hasCycles
            addActionListener { MavenBuildDialog(ideProject, order.entries.map(BuildOrderEntry::project)).show() }
        }
        val exportButton = JButton("Export to Excel", AllIcons.ToolbarDecorator.Export).apply {
            isEnabled = order.entries.isNotEmpty()
            addActionListener { exportWorkbook() }
        }
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            add(exportButton)
            add(buildButton)
        }
        return JPanel(BorderLayout(0, JBUI.scale(12))).apply {
            border = JBUI.Borders.empty(8, 4, 4, 4)
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(actions, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(900), JBUI.scale(560))
        }
    }

    private fun exportWorkbook() {
        val descriptor = FileSaverDescriptor("Export Build Order", "Save the build order as an Excel workbook", "xlsx")
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, ideProject)
            .save("build-order.xlsx") ?: return
        Files.write(target.file.toPath(), BuildOrderWorkbookExporter.export(order))
    }
}
