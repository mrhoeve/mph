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
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import nl.hicts.mph.intellij.services.*
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class VersionAlignmentPreviewDialog(project: Project, private val plan: VersionAlignmentPlan) : DialogWrapper(project) {
    private val files = JBList(plan.edits.map { it.project.pomPath })
    private val diff = DiffManager.getInstance().createRequestPanel(project, disposable, null)
    init {
        title = "Review Maven Version Changes"
        setOKButtonText("Apply Changes")
        okAction.putValue(javax.swing.Action.MNEMONIC_KEY, java.awt.event.KeyEvent.VK_A)
        isOKActionEnabled = plan.edits.isNotEmpty()
        files.accessibleContext.accessibleName = "POM files to change"
        files.addListSelectionListener {
            plan.edits.getOrNull(files.selectedIndex)?.let { edit ->
                val factory = DiffContentFactory.getInstance()
                diff.setRequest(SimpleDiffRequest(edit.project.pomPath, factory.create(edit.before), factory.create(edit.after), "Current (including local edits)", "After alignment").apply {
                    putUserData(DiffUserDataKeys.FORCE_READ_ONLY, true)
                })
            }
        }
        init()
        if (plan.edits.isNotEmpty()) files.selectedIndex = 0
    }
    override fun getPreferredFocusedComponent(): JComponent = files
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, 8)).apply {
        border = JBUI.Borders.empty(8)
        val notes = listOf("${plan.edits.size} POM file(s) will change. Review local version edits before applying. Changes can be undone together.") + plan.result.issues
        add(JBScrollPane(JBTextArea(notes.joinToString("\n"), 3, 70).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; accessibleContext.accessibleName = "Alignment summary and warnings" }), BorderLayout.NORTH)
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
): BulkVersionUpdateResult? = WorkspaceOperationCoordinator.run("Version alignment", owner) {
    try {
        validateState()
        val service = project.service<BulkVersionUpdateService>()
        val plan = service.prepare(request)
        if (plan.edits.isEmpty()) plan.result
        else if (VersionAlignmentPreviewDialog(project, plan).showAndGet()) {
            validateState()
            service.applyOwned(plan)
        }
        else null
    } catch (error: Exception) {
        Messages.showErrorDialog(project, error.message.orEmpty(), "Version Alignment Stopped")
        null
    }
}

