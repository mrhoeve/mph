package nl.hicts.mph.intellij.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.components.service
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import nl.hicts.mph.intellij.services.*
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

class VersionAlignmentPreviewDialog(project: Project, private val plan: VersionAlignmentPlan) : DialogWrapper(project) {
    private val files = JBList(plan.edits.map { it.project.pomPath })
    private val diff = DiffManager.getInstance().createRequestPanel(project, disposable, null)
    init {
        title = "Review Maven Version Changes"
        setOKButtonText("Apply Changes")
        isOKActionEnabled = plan.edits.isNotEmpty()
        files.accessibleContext.accessibleName = "POM files to change"
        files.addListSelectionListener {
            plan.edits.getOrNull(files.selectedIndex)?.let { edit ->
                val factory = DiffContentFactory.getInstance()
                diff.setRequest(SimpleDiffRequest(edit.project.pomPath, factory.create(edit.before), factory.create(edit.after), "Current (including local edits)", "After alignment"))
            }
        }
        init()
        if (plan.edits.isNotEmpty()) files.selectedIndex = 0
    }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        val notes = listOf("${plan.edits.size} POM file(s) will change. Review local version edits before applying. Changes can be undone together.") + plan.result.issues
        add(JBScrollPane(JBTextArea(notes.joinToString("\n"), 3, 70).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }), BorderLayout.NORTH)
        add(JBScrollPane(files).apply { preferredSize = Dimension(240, 350) }, BorderLayout.WEST)
        add(diff.component, BorderLayout.CENTER)
        preferredSize = Dimension(950, 600)
    }
}

internal fun reviewVersionAlignment(
    project: Project,
    request: BulkVersionUpdateRequest,
    owner: WorkspaceOperationCoordinator.Lease? = null,
): BulkVersionUpdateResult? = WorkspaceOperationCoordinator.run("Version alignment", owner) {
    try {
        val service = project.service<BulkVersionUpdateService>()
        val plan = service.prepare(request)
        if (plan.edits.isEmpty()) plan.result
        else if (VersionAlignmentPreviewDialog(project, plan).showAndGet()) service.applyOwned(plan)
        else null
    } catch (error: Exception) {
        Messages.showErrorDialog(project, error.message.orEmpty(), "Version Alignment Stopped")
        null
    }
}

