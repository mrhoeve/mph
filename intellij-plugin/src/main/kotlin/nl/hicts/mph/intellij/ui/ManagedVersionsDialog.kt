package nl.hicts.mph.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.services.ManagedPropertyFilter
import nl.hicts.mph.intellij.services.ManagedVersionAnalysis
import nl.hicts.mph.intellij.services.ManagedVersionProperty
import nl.hicts.mph.intellij.services.ManagedVersionService
import nl.hicts.mph.intellij.services.NexusIqSettings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableModel

class ManagedVersionsDialog(
    private val ideProject: Project,
    private val projectInfo: MavenProjectInfo,
) : DialogWrapper(ideProject) {
    private val service = ideProject.service<ManagedVersionService>()
    private var analysis: ManagedVersionAnalysis = service.inspect(projectInfo)
    private var displayed = emptyList<ManagedVersionProperty>()
    private val search = JBTextField()
    private val overridesOnly = JBCheckBox("Show only overrides")
    private val summary = JBLabel()
    private val model = object : DefaultTableModel(
        arrayOf("Property", "Value", "Source", "Security", "Recommended", "Comment"),
        0,
    ) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JBTable(model)
    private val overrideButton = JButton("Override", AllIcons.Actions.Edit)
    private val removeButton = JButton("Remove Override", AllIcons.Actions.GC)
    private val springBootButton = JButton("Upgrade Spring Boot", AllIcons.Nodes.PpLib)
    private val nexusButton = JButton("Check Nexus IQ", AllIcons.General.InspectionsEye)

    init {
        title = "Managed Component Versions"
        search.emptyText.text = "Filter by property or version"
        search.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = refreshTable()
        })
        overridesOnly.addActionListener { refreshTable() }
        table.selectionModel.addListSelectionListener { selectionChanged() }
        table.setShowGrid(false)
        table.rowHeight = JBUI.scale(28)
        table.columnModel.getColumn(0).preferredWidth = JBUI.scale(240)
        table.columnModel.getColumn(1).preferredWidth = JBUI.scale(180)
        table.columnModel.getColumn(2).preferredWidth = JBUI.scale(170)
        table.columnModel.getColumn(3).preferredWidth = JBUI.scale(260)
        table.columnModel.getColumn(4).preferredWidth = JBUI.scale(130)
        table.columnModel.getColumn(5).preferredWidth = JBUI.scale(220)
        overrideButton.addActionListener { overrideSelected() }
        removeButton.addActionListener { removeSelected() }
        springBootButton.addActionListener { upgradeSpringBoot() }
        nexusButton.addActionListener { checkNexusIq() }
        nexusButton.isEnabled = ApplicationManager.getApplication().service<NexusIqSettings>().configured()
        refreshTable()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val heading = JBLabel(projectInfo.artifactId, AllIcons.Nodes.Module, JBLabel.LEFT)
        heading.font = heading.font.deriveFont(Font.BOLD, heading.font.size2D + 3f)
        val filters = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            add(search, BorderLayout.CENTER)
            add(overridesOnly, BorderLayout.EAST)
        }
        val header = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            isOpaque = false
            add(heading, BorderLayout.NORTH)
            add(filters, BorderLayout.CENTER)
            add(summary, BorderLayout.SOUTH)
        }
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            add(nexusButton)
            add(springBootButton)
            add(removeButton)
            add(overrideButton)
        }
        return JPanel(BorderLayout(0, JBUI.scale(12))).apply {
            border = JBUI.Borders.empty(8, 4, 4, 4)
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(actions, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(930), JBUI.scale(580))
        }
    }

    private fun refreshTable() {
        displayed = ManagedPropertyFilter.filter(analysis.properties, overridesOnly.isSelected, search.text.trim())
        model.rowCount = 0
        displayed.forEach { property ->
            model.addRow(arrayOf(
                property.name,
                property.value,
                property.source,
                property.highestThreat?.let { "Threat $it" }.orEmpty(),
                property.remediationVersion.orEmpty(),
                property.comment.orEmpty(),
            ))
        }
        val overrides = analysis.properties.count(ManagedVersionProperty::isOverridden)
        summary.text = "${displayed.size} shown · ${analysis.properties.size} managed version properties · $overrides local overrides"
        summary.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        springBootButton.isVisible = analysis.springBoot != null
        springBootButton.text = analysis.springBoot?.let { "Upgrade Spring Boot ${it.currentVersion}" } ?: "Upgrade Spring Boot"
        selectionChanged()
    }

    private fun selectionChanged() {
        val selected = selectedProperty()
        overrideButton.isEnabled = selected != null
        removeButton.isEnabled = selected?.isOverridden == true
    }

    private fun selectedProperty(): ManagedVersionProperty? = table.selectedRow.takeIf { it >= 0 }
        ?.let(table::convertRowIndexToModel)
        ?.let(displayed::getOrNull)

    private fun overrideSelected() {
        val property = selectedProperty() ?: return
        val dialog = PropertyOverrideDialog(ideProject, property)
        if (!dialog.showAndGet()) return
        service.overrideProperty(projectInfo, property.name, dialog.value, dialog.comment)
        reload()
    }

    private fun removeSelected() {
        val property = selectedProperty()?.takeIf(ManagedVersionProperty::isOverridden) ?: return
        if (Messages.showYesNoDialog(
                ideProject,
                "Remove the local override for ${property.name}? The inherited value will become active after Maven reloads.",
                "Remove Maven Property Override",
                Messages.getQuestionIcon(),
            ) != Messages.YES
        ) return
        service.removeOverride(projectInfo, property.name)
        reload()
    }

    private fun upgradeSpringBoot() {
        val springBoot = analysis.springBoot ?: return
        val dialog = SpringBootUpgradeDialog(ideProject, springBoot)
        if (!dialog.showAndGet()) return
        service.upgradeSpringBoot(projectInfo, springBoot, dialog.selectedVersion)
        reload()
    }

    private fun checkNexusIq() {
        nexusButton.isEnabled = false
        summary.text = "Checking managed components with Nexus IQ…"
        object : Task.Backgroundable(ideProject, "Checking managed Maven versions", true) {
            private lateinit var enriched: ManagedVersionAnalysis
            override fun run(indicator: ProgressIndicator) {
                enriched = service.enrichWithNexus(analysis)
            }

            override fun onSuccess() {
                analysis = enriched
                refreshTable()
                nexusButton.isEnabled = true
            }

            override fun onThrowable(error: Throwable) {
                summary.text = "Nexus IQ check failed: ${error.message ?: error.javaClass.simpleName}"
                nexusButton.isEnabled = true
            }
        }.queue()
    }

    private fun reload() {
        analysis = service.inspect(projectInfo)
        refreshTable()
    }
}
