package nl.hicts.mph.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.services.IntellijNexusIqService
import nl.hicts.mph.intellij.services.NexusIqScanResult
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.table.DefaultTableModel

class NexusIqDialog(
    private val ideaProject: Project,
    private val mavenProject: MavenProjectInfo,
) : DialogWrapper(ideaProject) {
    private val status = JBLabel("Ready to evaluate ${mavenProject.artifactId}")
    private val scanButton = JButton("Run Nexus IQ scan", AllIcons.Actions.Execute)
    private val reportButton = JButton("Open report", AllIcons.General.Web).apply { isEnabled = false }
    private val log = JBTextArea().apply { isEditable = false; lineWrap = false }
    private val model = object : DefaultTableModel(arrayOf("Threat", "Component", "Policy", "Dependency", "Waived"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JBTable(model)
    private var reportUrl: String? = null

    init {
        title = "Nexus IQ"
        scanButton.addActionListener { runScan() }
        reportButton.addActionListener { reportUrl?.let(BrowserUtil::browse) }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val titleLabel = JBLabel(mavenProject.artifactId, AllIcons.General.InspectionsOK, JBLabel.LEFT).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 3f)
        }
        status.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        val header = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.NORTH)
            add(status, BorderLayout.SOUTH)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(scanButton)
            add(reportButton)
        }
        val split = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            ScrollPaneFactory.createScrollPane(table, true),
            ScrollPaneFactory.createScrollPane(log, true),
        ).apply {
            resizeWeight = 0.62
            border = JBUI.Borders.empty()
        }
        return JPanel(BorderLayout(0, JBUI.scale(12))).apply {
            border = JBUI.Borders.empty(8, 4, 4, 4)
            add(header, BorderLayout.NORTH)
            add(split, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(920), JBUI.scale(620))
        }
    }

    private fun runScan() {
        scanButton.isEnabled = false
        reportButton.isEnabled = false
        model.rowCount = 0
        log.text = ""
        status.text = "Running Nexus IQ evaluation…"
        object : Task.Backgroundable(ideaProject, "Scanning ${mavenProject.artifactId} with Nexus IQ", true) {
            private lateinit var result: NexusIqScanResult

            override fun run(indicator: ProgressIndicator) {
                result = ideaProject.service<IntellijNexusIqService>().scan(mavenProject, indicator) { text ->
                    ApplicationManager.getApplication().invokeLater { log.append(text) }
                }
            }

            override fun onSuccess() = showResult(result)

            override fun onThrowable(error: Throwable) {
                status.text = error.message ?: "Nexus IQ scan failed."
                log.append("\n${error.message ?: error.javaClass.simpleName}\n")
                scanButton.isEnabled = true
            }

            override fun onCancel() {
                status.text = "Scan cancelled"
                scanButton.isEnabled = true
            }
        }.queue()
    }

    private fun showResult(result: NexusIqScanResult) {
        val report = result.report
        report?.violations.orEmpty().forEach { violation ->
            model.addRow(
                arrayOf<Any>(
                    violation.threatLevel,
                    violation.component,
                    violation.policy,
                    if (violation.direct) "Direct" else "Transitive",
                    if (violation.waived) "Yes" else "No",
                ),
            )
        }
        reportUrl = report?.reportUrl
        reportButton.isEnabled = reportUrl != null
        status.text = if (result.exitCode == 0) {
            if (report == null) "Scan completed · report details unavailable"
            else "Scan completed · ${report.critical} critical · ${report.severe} severe · ${report.moderate} moderate · ${report.low} low"
        } else {
            "Scan failed with Maven exit code ${result.exitCode}"
        }
        scanButton.isEnabled = true
    }
}
