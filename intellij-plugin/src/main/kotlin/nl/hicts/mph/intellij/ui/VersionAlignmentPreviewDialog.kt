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
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import nl.hicts.mph.intellij.services.*
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel

class VersionAlignmentPreviewDialog(project: Project, private val plan: VersionAlignmentPlan) : DialogWrapper(project) {
    private data class ReviewRow(val file: Int, val change: Int? = null)

    private val included = plan.edits.map { BooleanArray(it.changes.size) { true } }
    private val fileIndexes = plan.edits.mapIndexed { index, edit -> edit.project.pomPath to index }.toMap()
    private val rows = plan.edits.flatMapIndexed { file, edit ->
        listOf(ReviewRow(file)) + edit.changes.indices.map { ReviewRow(file, it) }
    }
    internal val files = JBTable(object : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = if (column == 0) "Apply" else "POM and version changes"
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) Boolean::class.javaObjectType else String::class.java
        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex == 0
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            val checks = included[row.file]
            if (columnIndex == 0) return row.change?.let { checks[it] } ?: checks.all { it }
            val edit = plan.edits[row.file]
            val change = row.change?.let(edit.changes::get)
            return if (change == null) "${edit.project.artifactId}/pom.xml" else {
                val references = if (change.referenceCount > 1) " · ${change.referenceCount} references" else ""
                "    ${change.label} · ${change.oldVersion} → ${change.newVersion}$references"
            }
        }
        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex != 0) return
            val row = rows[rowIndex]
            val checks = included[row.file]
            val change = row.change
            if (change == null) checks.fill(value == true) else checks[change] = value == true
            val firstRow = rows.indexOf(ReviewRow(row.file))
            fireTableRowsUpdated(firstRow, firstRow + checks.size)
            updateSummary()
            updateDiff()
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
        files.accessibleContext.accessibleName = "POM files and individual changes to apply"
        val checkboxRenderer = files.getDefaultRenderer(Boolean::class.javaObjectType)
        val mixed = JBLabel("⊟", SwingConstants.CENTER).apply {
            isOpaque = true
            accessibleContext.accessibleName = "Partially selected"
        }
        files.columnModel.getColumn(0).cellRenderer = javax.swing.table.TableCellRenderer { table, value, selected, focused, rowIndex, column ->
            val row = rows[rowIndex]
            val checks = included[row.file]
            if (row.change == null && checks.any { it } && checks.any { !it }) {
                mixed.background = if (selected) table.selectionBackground else table.background
                mixed.foreground = if (selected) table.selectionForeground else table.foreground
                mixed
            } else {
                checkboxRenderer.getTableCellRendererComponent(table, value, selected, focused, rowIndex, column)
            }
        }
        files.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) updateDiff()
        }
        updateSummary()
        init()
        if (plan.edits.isNotEmpty()) files.setRowSelectionInterval(0, 0)
    }

    internal fun previewContent(file: Int): String = plan.edits[file].let { edit ->
        applyPomVersionChanges(edit.before, edit.changes.filterIndexed { index, _ -> included[file][index] })
    }

    private fun updateDiff() {
        val row = rows.getOrNull(files.selectedRow) ?: return
        val edit = plan.edits[row.file]
        val factory = DiffContentFactory.getInstance()
        diff.setRequest(SimpleDiffRequest(edit.project.pomPath, factory.create(edit.before),
            factory.create(previewContent(row.file)), "Current (including local edits)", "After selected changes").apply {
            putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
        })
    }

    private fun updateSummary() {
        val count = included.sumOf { checks -> checks.count { it } }
        val total = included.sumOf { it.size }
        isOKActionEnabled = count > 0
        val notes = mutableListOf("$count of $total changes selected. Applied changes can be undone together.")
        if (count != total) {
            notes += "Excluded changes stay untouched. Check that the remaining project and dependency versions still agree."
        }
        summary.text = (notes + plan.result.issues).joinToString("\n")
        summary.caretPosition = 0
    }

    internal fun selectedPlan(): VersionAlignmentPlan =
        plan.withSelectedChanges { edit, index -> included[fileIndexes.getValue(edit.project.pomPath)][index] }

    override fun getPreferredFocusedComponent(): JComponent = files
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        border = JBUI.Borders.empty(8)
        add(JBScrollPane(summary), BorderLayout.NORTH)
        add(JBScrollPane(files).apply { preferredSize = JBUI.size(320, 350) }, BorderLayout.WEST)
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

