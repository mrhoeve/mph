package nl.hicts.mph.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.services.BulkVersionMode
import nl.hicts.mph.intellij.services.BulkVersionUpdateRequest
import nl.hicts.mph.intellij.services.BulkVersionUpdateService
import nl.hicts.mph.intellij.services.GitRebaseListener
import nl.hicts.mph.intellij.services.GitRebasePlan
import nl.hicts.mph.intellij.services.GitRebaseService
import nl.hicts.mph.intellij.services.GitRebaseStatus
import nl.hicts.mph.intellij.services.GitRepositoryPlan
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

class GitRebaseDialog(
    private val ideProject: Project,
    private val plan: GitRebasePlan,
    private val workspaceProjects: List<MavenProjectInfo>,
) : DialogWrapper(ideProject) {
    private val gitService = ideProject.service<GitRebaseService>()
    private val listModel = DefaultListModel<GitRebaseRow>()
    private val repositoryList = JBList(listModel)
    private val statusLabel = JBLabel("Ready")
    private val startButton = JButton("Stash and Rebase", AllIcons.Vcs.Branch)
    private val stopButton = JButton("Stop", AllIcons.Actions.Suspend)
    @Volatile
    private var running = false

    init {
        title = "Synchronize Feature Branches with develop"
        plan.repositories.forEach { listModel.addElement(GitRebaseRow(it, GitRebaseStatus.PENDING, "Waiting")) }
        repositoryList.cellRenderer = GitRebaseRowRenderer()
        stopButton.isEnabled = false
        startButton.addActionListener { start() }
        stopButton.addActionListener { stop() }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(14)))
        panel.border = JBUI.Borders.empty(8, 4, 4, 4)
        panel.add(createHeader(), BorderLayout.NORTH)
        repositoryList.visibleRowCount = 9
        panel.add(JBScrollPane(repositoryList), BorderLayout.CENTER)

        val controls = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0))
        controls.add(stopButton)
        controls.add(startButton)
        panel.add(controls, BorderLayout.SOUTH)
        panel.preferredSize = Dimension(JBUI.scale(780), JBUI.scale(480))
        return panel
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction.apply { putValue(Action.NAME, "Close") })

    override fun doCancelAction() {
        if (running) stop()
        super.doCancelAction()
    }

    override fun dispose() {
        gitService.cancel()
        super.dispose()
    }

    private fun createHeader(): JComponent {
        val heading = JBLabel("Rebase on develop", AllIcons.Vcs.Branch, JBLabel.LEFT)
        heading.font = heading.font.deriveFont(Font.BOLD, heading.font.size2D + 3f)
        val explanation = JBLabel(
            "Prefix '${plan.prefix}' will be reapplied and all dependents aligned only when every repository succeeds.",
        )
        explanation.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(heading, BorderLayout.NORTH)
            add(explanation, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
    }

    private fun start() {
        if (running) return
        running = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusLabel.text = "Rebasing repositories sequentially…"
        plan.repositories.indices.forEach { updateRow(it, GitRebaseStatus.PENDING, "Waiting") }
        SynchronizeTask().queue()
    }

    private inner class SynchronizeTask : Task.Backgroundable(
        ideProject,
        "Synchronizing repositories with develop",
        true,
    ) {
        private var allSucceeded = false
        private var cancelled = false

        override fun run(indicator: ProgressIndicator) {
            val listener = GitRebaseListener { repository, status, message ->
                ApplicationManager.getApplication().invokeLater {
                    updateRepositoryRow(repository, status, message)
                }
            }
            val results = gitService.rebase(plan, indicator, listener)
            results.forEach { result ->
                ApplicationManager.getApplication().invokeLater {
                    updateRepositoryRow(result.repository, result.status, result.message)
                }
            }
            cancelled = indicator.isCanceled || results.any { it.status == GitRebaseStatus.CANCELLED }
            allSucceeded = results.isNotEmpty() && results.all { it.status == GitRebaseStatus.SUCCESS }
        }

        override fun onSuccess() {
            if (allSucceeded) {
                alignVersions()
            } else {
                statusLabel.text = if (cancelled) {
                    "Cancelled. Version alignment was skipped."
                } else {
                    "Finished with issues. Resolve them before aligning versions."
                }
            }
        }

        override fun onFinished() {
            running = false
            startButton.isEnabled = true
            stopButton.isEnabled = false
        }
    }

    private fun updateRepositoryRow(repository: GitRepositoryPlan, status: GitRebaseStatus, message: String) {
        val index = plan.repositories.indexOf(repository)
        if (index >= 0) updateRow(index, status, message)
    }

    private fun alignVersions() {
        statusLabel.text = "Reapplying '${plan.prefix}' and aligning dependent versions…"
        val alignment = ideProject.service<BulkVersionUpdateService>().update(
            BulkVersionUpdateRequest(
                selectedProjects = plan.alignmentProjects,
                workspaceProjects = workspaceProjects,
                prefix = plan.prefix,
                mode = BulkVersionMode.ADD_PREFIX,
                updateDependents = true,
                normalizePrefix = true,
            ),
        )
        statusLabel.text = if (alignment.issues.isEmpty()) {
            "Completed. Git and ${alignment.updatedProjectCount} project version(s) are aligned."
        } else {
            "Completed with ${alignment.issues.size} version-alignment warning(s)."
        }
    }

    private fun stop() {
        gitService.cancel()
        statusLabel.text = "Stopping after the active Git command…"
        stopButton.isEnabled = false
    }

    private fun updateRow(index: Int, status: GitRebaseStatus, message: String) {
        val row = listModel.get(index)
        listModel.set(index, row.copy(status = status, message = message))
    }
}

private data class GitRebaseRow(
    val repository: GitRepositoryPlan,
    val status: GitRebaseStatus,
    val message: String,
)

private class GitRebaseRowRenderer : ColoredListCellRenderer<GitRebaseRow>() {
    override fun customizeCellRenderer(
        list: JList<out GitRebaseRow>,
        value: GitRebaseRow,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        icon = when (value.status) {
            GitRebaseStatus.PENDING -> AllIcons.General.InspectionsPause
            GitRebaseStatus.RUNNING -> AllIcons.Process.Step_1
            GitRebaseStatus.SUCCESS -> AllIcons.General.InspectionsOK
            GitRebaseStatus.CONFLICT -> AllIcons.General.Warning
            GitRebaseStatus.SKIPPED -> AllIcons.General.Warning
            GitRebaseStatus.FAILED -> AllIcons.General.Error
            GitRebaseStatus.CANCELLED -> AllIcons.Actions.Cancel
        }
        border = JBUI.Borders.empty(7, 8)
        append(value.repository.artifactId, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        append("  ${value.status.name.lowercase()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append("  ${value.message}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        toolTipText = value.repository.rootPath
    }
}
