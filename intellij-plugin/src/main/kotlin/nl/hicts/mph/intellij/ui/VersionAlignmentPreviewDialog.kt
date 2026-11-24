package nl.hicts.mph.intellij.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.util.ui.JBUI
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.components.service
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import nl.hicts.mph.intellij.services.*
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

class VersionAlignmentPreviewDialog(project: Project, private val plan: VersionAlignmentPlan) : DialogWrapper(project) {
    private val included = BooleanArray(plan.edits.size) { true }
    internal val files = JBTable(object : AbstractTableModel() {
        override fun getRowCount() = plan.edits.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = if (column == 0) "Apply" else "POM file"
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) Boolean::class.javaObjectType else String::class.java
        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == 0
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
            if (columnIndex == 0) included[rowIndex] else plan.edits[rowIndex].project.pomPath
        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != 0) return
            included[rowIndex] = value == true
            fireTableCellUpdated(rowIndex, columnIndex)
            updateSummary()
        }
    })
    private val summary = JBTextArea("", 3, 70).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        accessibleContext.accessibleName = "Alignment summary and warnings"
    }
    private val diff = DiffManager.getInstance().createRequestPanel(project, disposable, null)
    init {
        title = "Review Maven Version Changes"
        setOKButtonText("Apply Changes")
        okAction.putValue(javax.swing.Action.MNEMONIC_KEY, java.awt.event.KeyEvent.VK_A)
        files.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        files.columnModel.getColumn(0).maxWidth = JBUI.scale(60)
        files.accessibleContext.accessibleName = "POM files to change"
        files.selectionModel.addListSelectionListener {
            plan.edits.getOrNull(files.selectedRow)?.let { edit ->
                val factory = DiffContentFactory.getInstance()
                diff.setRequest(SimpleDiffRequest(edit.project.pomPath, factory.create(edit.before), factory.create(edit.after), "Current (including local edits)", "After alignment").apply {
                    putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
                })
            }
        }
        updateSummary()
        init()
        if (plan.edits.isNotEmpty()) files.setRowSelectionInterval(0, 0)
    }

    private fun updateSummary() {
        val count = included.count { it }
        isOKActionEnabled = count > 0
        val notes = mutableListOf("$count of ${plan.edits.size} POM file(s) selected. Applied changes can be undone together.")
        if (count != plan.edits.size) {
            notes += "Excluded POMs stay unchanged. Included diffs are not recalculated; check references to excluded projects."
        }
        summary.text = (notes + plan.result.issues).joinToString("\n")
        summary.caretPosition = 0
    }

    internal fun selectedPlan(): VersionAlignmentPlan =
        plan.including(plan.edits.filterIndexed { index, _ -> included[index] }.map { it.project.pomPath }.toSet())

    override fun getPreferredFocusedComponent(): JComponent = files
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        border = JBUI.Borders.empty(8)
        add(JBScrollPane(summary), BorderLayout.NORTH)
        add(JBScrollPane(files).apply { preferredSize = JBUI.size(220, 350) }, BorderLayout.WEST)
        add(diff.component, BorderLayout.CENTER)
        preferredSize = JBUI.size(950, 600)
    }
}

internal fun reviewVersionAlignment(
    project: Project,
    request: BulkVersionUpdateRequest,
    owner: WorkspaceOperationCoordinator.Lease? = null,
    validateState: () -> Unit = {},
    reportFailure: (Exception) -> Unit = { error ->
        Messages.showErrorDialog(project, error.message.orEmpty(), "Version Alignment Stopped")
    },
): BulkVersionUpdateResult? = WorkspaceOperationCoordinator.run("Version alignment", owner) {
    try {
        validateState()
        val service = project.service<BulkVersionUpdateService>()
        val plan = service.prepare(request)
        if (plan.edits.isEmpty()) plan.result
        else {
            val dialog = VersionAlignmentPreviewDialog(project, plan)
            if (!dialog.showAndGet()) null
            else {
                validateState()
                service.applyOwned(dialog.selectedPlan())
            }
        }
    } catch (error: Exception) {
        reportFailure(error)
        null
    }
}

