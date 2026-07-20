package nl.hicts.mph.intellij.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.services.MavenBuildListener
import nl.hicts.mph.intellij.services.MavenBuildOptions
import nl.hicts.mph.intellij.services.MavenBuildService
import nl.hicts.mph.intellij.services.MavenBuildStatus
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.Action
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

class MavenBuildDialog(
    private val ideProject: Project,
    projects: List<MavenProjectInfo>,
) : DialogWrapper(ideProject) {
    private val buildService = ideProject.service<MavenBuildService>()
    private val selectedProjects = projects.distinctBy(MavenProjectInfo::pomPath)
    private val goalsField = JBTextField("clean install")
    private val skipUnitTests = JBCheckBox("Skip unit tests", true)
    private val skipIntegrationTests = JBCheckBox("Skip integration tests", true)
    private val startButton = JButton("Run Build", AllIcons.Actions.Execute)
    private val stopButton = JButton("Stop", AllIcons.Actions.Suspend)
    private val statusLabel = JBLabel("Ready to build ${selectedProjects.size} Maven project(s)")
    private val listModel = DefaultListModel<MavenBuildRow>()
    private val projectList = JBList(listModel)
    private val console = TextConsoleBuilderFactory.getInstance().createBuilder(ideProject).console
    @Volatile
    private var building = false

    init {
        title = "Build Maven Projects"
        selectedProjects.forEach { listModel.addElement(MavenBuildRow(it, MavenBuildStatus.PENDING)) }
        projectList.cellRenderer = MavenBuildRowRenderer()
        stopButton.isEnabled = false
        startButton.addActionListener { startBuild() }
        stopButton.addActionListener { stopBuild() }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(12)))
        panel.border = JBUI.Borders.empty(8, 4, 4, 4)
        panel.add(createHeader(), BorderLayout.NORTH)

        val buildPanel = JPanel(BorderLayout(JBUI.scale(10), 0))
        val options = FormBuilder.createFormBuilder()
            .addLabeledComponent("Maven goals:", goalsField, 1, false)
            .addComponent(skipUnitTests)
            .addComponent(skipIntegrationTests)
            .panel
        options.preferredSize = Dimension(JBUI.scale(270), JBUI.scale(150))
        projectList.visibleRowCount = 5
        buildPanel.add(options, BorderLayout.WEST)
        buildPanel.add(JBScrollPane(projectList), BorderLayout.CENTER)

        val center = JPanel(BorderLayout(0, JBUI.scale(10)))
        center.add(buildPanel, BorderLayout.NORTH)
        center.add(console.component, BorderLayout.CENTER)
        panel.add(center, BorderLayout.CENTER)

        val controls = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0))
        controls.add(stopButton)
        controls.add(startButton)
        panel.add(controls, BorderLayout.SOUTH)
        panel.preferredSize = Dimension(JBUI.scale(860), JBUI.scale(590))
        return panel
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction.apply { putValue(Action.NAME, "Close") })

    override fun doCancelAction() {
        if (building) stopBuild()
        super.doCancelAction()
    }

    override fun dispose() {
        buildService.cancel()
        console.dispose()
        super.dispose()
    }

    private fun createHeader(): JComponent {
        val heading = JBLabel("Maven build", AllIcons.Nodes.PpLibFolder, JBLabel.LEFT)
        heading.font = heading.font.deriveFont(Font.BOLD, heading.font.size2D + 3f)
        val header = JPanel(BorderLayout())
        header.isOpaque = false
        header.add(heading, BorderLayout.WEST)
        header.add(statusLabel, BorderLayout.EAST)
        return header
    }

    private fun startBuild() {
        if (building) return
        val goals = goalsField.text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        if (goals.isEmpty()) {
            statusLabel.text = "Enter at least one Maven goal"
            return
        }
        building = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
        goalsField.isEnabled = false
        skipUnitTests.isEnabled = false
        skipIntegrationTests.isEnabled = false
        console.clear()
        selectedProjects.indices.forEach { updateRow(it, MavenBuildStatus.PENDING) }

        val options = MavenBuildOptions(
            goals = goals,
            skipUnitTests = skipUnitTests.isSelected,
            skipIntegrationTests = skipIntegrationTests.isSelected,
        )
        object : Task.Backgroundable(ideProject, "Building Maven projects", true) {
            private var failed = 0
            private var cancelled = false

            override fun run(indicator: ProgressIndicator) {
                val listener = MavenBuildListener { project, status, text ->
                    ApplicationManager.getApplication().invokeLater {
                        val index = selectedProjects.indexOfFirst { it.pomPath == project.pomPath }
                        if (index >= 0) updateRow(index, status)
                        text?.let { output ->
                            val type = if (output.startsWith("[error]")) {
                                ConsoleViewContentType.ERROR_OUTPUT
                            } else {
                                ConsoleViewContentType.NORMAL_OUTPUT
                            }
                            console.print(output, type)
                        }
                    }
                }
                val results = buildService.build(selectedProjects, options, indicator, listener)
                failed = results.count { it.status == MavenBuildStatus.FAILED }
                cancelled = indicator.isCanceled || results.any { it.status == MavenBuildStatus.CANCELLED }
            }

            override fun onFinished() {
                building = false
                startButton.isEnabled = true
                stopButton.isEnabled = false
                goalsField.isEnabled = true
                skipUnitTests.isEnabled = true
                skipIntegrationTests.isEnabled = true
                statusLabel.text = when {
                    cancelled -> "Build cancelled"
                    failed > 0 -> "$failed project(s) failed"
                    else -> "Build completed successfully"
                }
            }
        }.queue()
    }

    private fun stopBuild() {
        buildService.cancel()
        statusLabel.text = "Stopping build…"
        stopButton.isEnabled = false
    }

    private fun updateRow(index: Int, status: MavenBuildStatus) {
        val row = listModel.get(index)
        listModel.set(index, row.copy(status = status))
    }
}

private data class MavenBuildRow(val project: MavenProjectInfo, val status: MavenBuildStatus)

private class MavenBuildRowRenderer : ColoredListCellRenderer<MavenBuildRow>() {
    override fun customizeCellRenderer(
        list: JList<out MavenBuildRow>,
        value: MavenBuildRow,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        icon = when (value.status) {
            MavenBuildStatus.PENDING -> AllIcons.General.InspectionsPause
            MavenBuildStatus.RUNNING -> AllIcons.Process.Step_1
            MavenBuildStatus.SUCCESS -> AllIcons.General.InspectionsOK
            MavenBuildStatus.FAILED -> AllIcons.General.Error
            MavenBuildStatus.CANCELLED -> AllIcons.Actions.Cancel
        }
        border = JBUI.Borders.empty(6, 8)
        append(value.project.artifactId, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        append("  ${value.status.name.lowercase()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        toolTipText = value.project.pomPath
    }
}
