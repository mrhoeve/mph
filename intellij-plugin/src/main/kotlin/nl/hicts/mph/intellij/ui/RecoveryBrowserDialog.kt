package nl.hicts.mph.intellij.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import nl.hicts.mph.intellij.services.AlignmentRecoveryStore
import nl.hicts.mph.intellij.services.RecoveryCatalog
import nl.hicts.mph.intellij.services.RecoveryRun
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.*

class RecoveryBrowserDialog(private val project: Project, repositoryRoots: List<String>) : DialogWrapper(project) {
    private val catalog = RecoveryCatalog(listOf(AlignmentRecoveryStore.root()) + repositoryRoots.map { Path.of(it, ".git", "mph-recovery") })
    private val model = DefaultListModel<RecoveryRun>()
    private val runs = JBList(model)
    private val details = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val delete = JButton("Delete Reviewed Copies…").apply { isEnabled = false }
    init {
        title = "MPH Recovery Copies"
        runs.accessibleContext.accessibleName = "Retained recovery runs"
        details.accessibleContext.accessibleName = "Recovery instructions and original file locations"
        runs.addListSelectionListener { details.text = runs.selectedValue?.details.orEmpty(); details.caretPosition = 0; delete.isEnabled = runs.selectedValue != null }
        delete.addActionListener {
            val selected = runs.selectedValue ?: return@addActionListener
            if (Messages.showYesNoDialog(project, "Delete the ${selected.files.size} reviewed recovery files in ${selected.directory}?\n\nThis cannot be undone. Git recovery refs and stashes will remain.", "Delete Recovery Copies", Messages.getWarningIcon()) == Messages.YES) {
                try { catalog.deleteReviewed(selected); reload() }
                catch (error: Exception) { Messages.showErrorDialog(project, error.message.orEmpty(), "Recovery Cleanup Stopped"); reload() }
            }
        }
        init()
        reload()
    }
    private fun reload() {
        model.clear()
        try { catalog.list().forEach(model::addElement) }
        catch (error: Exception) { details.text = "Could not read recovery runs: ${error.message}" }
        if (!model.isEmpty) runs.selectedIndex = 0 else details.text = "No recovery copies found for this workspace or IDE configuration."
    }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(8, 8)).apply {
        add(JBScrollPane(runs).apply { preferredSize = Dimension(300, 400) }, BorderLayout.WEST)
        add(JBScrollPane(details), BorderLayout.CENTER)
        add(delete, BorderLayout.SOUTH)
        preferredSize = Dimension(900, 500)
    }
    override fun createActions(): Array<Action> = arrayOf(cancelAction.apply { putValue(Action.NAME, "Close") })
}
