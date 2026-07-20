package nl.hicts.mph.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import nl.hicts.mph.intellij.icons.MphIcons
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.services.BulkVersionMode
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

class BulkVersionUpdateDialog(
    project: Project,
    private val projects: List<MavenProjectInfo>,
) : DialogWrapper(project) {
    private val modeField = JComboBox(DefaultComboBoxModel(BulkVersionMode.entries.toTypedArray()))
    private val prefixField = JBTextField()
    private val updateDependentsField = JBCheckBox("Update references in all linked Maven projects", true)

    val mode: BulkVersionMode
        get() = modeField.selectedItem as BulkVersionMode
    val prefix: String
        get() = prefixField.text.trim()
    val updateDependents: Boolean
        get() = updateDependentsField.isSelected

    init {
        title = "Align Maven Project Versions"
        setOKButtonText("Update ${projects.size} ${if (projects.size == 1) "Project" else "Projects"}")
        modeField.renderer = BulkVersionModeRenderer()
        prefixField.emptyText.text = "For example: PREFIX-1234-"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val content = JPanel(BorderLayout(0, JBUI.scale(14)))
        content.border = JBUI.Borders.empty(8, 4, 4, 4)
        content.add(createHeader(), BorderLayout.NORTH)
        content.add(createForm(), BorderLayout.CENTER)
        content.preferredSize = Dimension(JBUI.scale(640), JBUI.scale(430))
        return content
    }

    override fun getPreferredFocusedComponent(): JComponent = prefixField

    override fun doValidate(): ValidationInfo? = when {
        prefix.isBlank() -> ValidationInfo("Enter the prefix to add or remove.", prefixField)
        prefix.any { it == '<' || it == '>' || it == '&' } ->
            ValidationInfo("The prefix contains characters that are unsafe in XML.", prefixField)
        else -> null
    }

    private fun createHeader(): JComponent {
        val heading = JBLabel("Bulk version alignment", MphIcons.Mph, JBLabel.LEFT)
        heading.font = heading.font.deriveFont(Font.BOLD, heading.font.size2D + 3f)
        heading.iconTextGap = JBUI.scale(12)

        val explanation = JBLabel(
            "Versions are changed surgically and remain uncommitted. The complete operation can be undone once.",
        )
        explanation.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND

        return JPanel(BorderLayout(0, JBUI.scale(5))).apply {
            isOpaque = false
            add(heading, BorderLayout.NORTH)
            add(explanation, BorderLayout.CENTER)
        }
    }

    private fun createForm(): JComponent {
        val projectList = JBList(projects)
        projectList.visibleRowCount = 7
        projectList.cellRenderer = BulkProjectRenderer()
        val scrollPane = JBScrollPane(projectList)
        scrollPane.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(190))

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Operation:", modeField, 1, false)
            .addLabeledComponent("Version prefix:", prefixField, 1, false)
            .addComponent(updateDependentsField, 8)
            .addSeparator(10)
            .addLabeledComponent("Selected projects:", scrollPane, 1, true)
            .panel
    }
}

private class BulkVersionModeRenderer : javax.swing.DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ) = super.getListCellRendererComponent(
        list,
        (value as? BulkVersionMode)?.displayName ?: value,
        index,
        isSelected,
        cellHasFocus,
    )
}

private class BulkProjectRenderer : ColoredListCellRenderer<MavenProjectInfo>() {
    override fun customizeCellRenderer(
        list: JList<out MavenProjectInfo>,
        value: MavenProjectInfo,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        icon = AllIcons.Nodes.Module
        border = JBUI.Borders.empty(6, 8)
        append(value.artifactId, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        value.version?.takeIf(String::isNotBlank)?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
        value.gitRootPath?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
        toolTipText = value.pomPath
    }
}
