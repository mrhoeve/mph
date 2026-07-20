package nl.hicts.mph.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import nl.hicts.mph.intellij.icons.MphIcons
import nl.hicts.mph.intellij.model.DependentMavenProject
import nl.hicts.mph.intellij.model.DependentProjectsAnalysis
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class DependentProjectsDialog(
    project: Project,
    private val analysis: DependentProjectsAnalysis,
) : DialogWrapper(project) {
    internal val canApplyUpdate: Boolean =
        analysis.dependents.isNotEmpty() && !analysis.target.version.isNullOrBlank()

    init {
        title = "Update Dependent Maven Projects"
        setOKButtonText(
            if (canApplyUpdate) {
                "Update ${analysis.dependents.size} ${if (analysis.dependents.size == 1) "Project" else "Projects"}"
            } else {
                "Close"
            },
        )
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(16)))
        panel.border = JBUI.Borders.empty(8, 4, 4, 4)
        panel.add(createHeader(), BorderLayout.NORTH)
        panel.add(createProjectList(), BorderLayout.CENTER)
        panel.preferredSize = Dimension(JBUI.scale(680), JBUI.scale(390))
        return panel
    }

    override fun createActions(): Array<Action> =
        if (canApplyUpdate) arrayOf(okAction, cancelAction) else arrayOf(okAction)

    private fun createHeader(): JComponent {
        val text = JPanel()
        text.layout = BoxLayout(text, BoxLayout.Y_AXIS)
        text.isOpaque = false

        val coordinates = analysis.target.coordinates
        val titleLabel = JBLabel(coordinates)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, titleLabel.font.size2D + 3f)
        titleLabel.alignmentX = Component.LEFT_ALIGNMENT

        val versionText = analysis.target.version?.takeIf(String::isNotBlank)?.let { "Version $it" }
            ?: "Version is inherited or unresolved"
        val versionLabel = JBLabel(versionText)
        versionLabel.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        versionLabel.alignmentX = Component.LEFT_ALIGNMENT

        val explanation = JBLabel(explanationText())
        explanation.alignmentX = Component.LEFT_ALIGNMENT

        text.add(titleLabel)
        text.add(Box.createVerticalStrut(JBUI.scale(3)))
        text.add(versionLabel)
        text.add(Box.createVerticalStrut(JBUI.scale(10)))
        text.add(explanation)

        val header = JPanel(BorderLayout(JBUI.scale(14), 0))
        header.isOpaque = false
        header.add(JBLabel(MphIcons.Mph), BorderLayout.WEST)
        header.add(text, BorderLayout.CENTER)
        return header
    }

    private fun createProjectList(): JComponent {
        val projects = JBList(analysis.dependents)
        projects.selectionMode = ListSelectionModel.SINGLE_SELECTION
        projects.visibleRowCount = 9
        projects.emptyText.text = "No dependent projects found in the linked Maven workspace"
        projects.cellRenderer = DependentProjectListRenderer()

        val scrollPane = JBScrollPane(projects)
        scrollPane.border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1)
        return scrollPane
    }

    private fun explanationText(): String = when (analysis.dependents.size) {
        0 -> "No linked Maven projects reference this project."
        1 -> "1 linked Maven project references this project."
        else -> "${analysis.dependents.size} linked Maven projects reference this project."
    } + if (canApplyUpdate) {
        " Review the projects below before updating their POM files."
    } else if (analysis.target.version.isNullOrBlank()) {
        " The selected project has no resolved version to apply."
    } else {
        ""
    }
}

internal class DependentProjectListRenderer : ColoredListCellRenderer<DependentMavenProject>() {
    override fun customizeCellRenderer(
        list: javax.swing.JList<out DependentMavenProject>,
        value: DependentMavenProject,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        icon = AllIcons.Nodes.Module
        border = JBUI.Borders.empty(7, 8)
        append(value.project.artifactId, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        value.project.version?.takeIf(String::isNotBlank)?.let {
            append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        append("   ")
        append(
            value.references.joinToString { it.displayName },
            SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
        )
        toolTipText = value.project.pomPath
    }
}
