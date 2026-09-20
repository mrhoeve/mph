package nl.hicts.mph.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import nl.hicts.mph.intellij.icons.MphIcons
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.services.BulkVersionMode
import nl.hicts.mph.intellij.services.BulkVersionUpdateRequest
import nl.hicts.mph.intellij.services.BulkVersionUpdateService
import nl.hicts.mph.intellij.services.MavenModelRefreshService
import nl.hicts.mph.intellij.services.IdeaProjectDiscoveryService
import nl.hicts.mph.intellij.services.WorkspaceOperationCoordinator
import nl.hicts.mph.intellij.services.IdeaConflictResolutionService
import nl.hicts.mph.intellij.services.GitRepositoryResult
import nl.hicts.mph.intellij.services.GitWorkspaceFingerprint
import com.intellij.openapi.progress.ProgressManager
import nl.hicts.mph.intellij.services.GitRecoverySnapshots
import nl.hicts.mph.intellij.services.GitRebaseListener
import nl.hicts.mph.intellij.services.GitRebasePlan
import nl.hicts.mph.intellij.services.GitRebaseService
import nl.hicts.mph.intellij.services.GitRebaseStatus
import nl.hicts.mph.intellij.services.GitRepositoryPlan
import java.nio.file.Path
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
    private val reloadMaven: (((() -> Unit), (Throwable) -> Unit) -> Unit)? = null,
    private val discover: (() -> List<MavenProjectInfo>)? = null,
    private val align: ((List<MavenProjectInfo>, List<MavenProjectInfo>) -> Unit)? = null,
    private val conflictResolver: ((String) -> String)? = null,
) : DialogWrapper(ideProject) {
    private val gitService = ideProject.service<GitRebaseService>()
    private val listModel = DefaultListModel<GitRebaseRow>()
    private val repositoryList = JBList(listModel)
    private val statusLabel = JBLabel("Ready")
    private val recoveryDetails = JBTextArea(5, 60).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    private val startButton = JButton("Stash and Rebase", MphIcons.SyncDevelop)
    private val resolveButton = JButton("Resolve Conflicts…", AllIcons.Vcs.Merge)
    private val stopButton = JButton("Stop", MphIcons.Stop)
    @Volatile
    private var running = false
    @Volatile private var stopRequested = false
    private var operation: WorkspaceOperationCoordinator.Lease? = null
    @Volatile private var gitIndicator: ProgressIndicator? = null
    private var refreshing = false
    private var expectedRepositories = emptyList<GitWorkspaceFingerprint>()
    internal val statusText: String get() = statusLabel.text

    init {
        title = "Synchronize Feature Branches with develop"
        repositoryList.accessibleContext.accessibleName = "Repositories to synchronize"
        recoveryDetails.accessibleContext.accessibleName = "Synchronization recovery instructions"
        startButton.mnemonic = java.awt.event.KeyEvent.VK_S
        stopButton.mnemonic = java.awt.event.KeyEvent.VK_T
        resolveButton.mnemonic = java.awt.event.KeyEvent.VK_R
        plan.repositories.forEach { listModel.addElement(GitRebaseRow(it, GitRebaseStatus.PENDING, "Waiting")) }
        repositoryList.putClientProperty(AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)
        repositoryList.cellRenderer = GitRebaseRowRenderer()
        repositoryList.addListSelectionListener { showRecoveryDetails() }
        if (!listModel.isEmpty) repositoryList.selectedIndex = 0
        stopButton.isEnabled = false
        resolveButton.isEnabled = false
        resolveButton.addActionListener { resolveSelectedConflicts() }
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
        controls.add(resolveButton)
        controls.add(stopButton)
        controls.add(startButton)
        panel.add(JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            add(JBScrollPane(recoveryDetails), BorderLayout.CENTER)
            add(controls, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        panel.preferredSize = Dimension(JBUI.scale(780), JBUI.scale(480))
        return panel
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction.apply { putValue(Action.NAME, "Close") })

    override fun doCancelAction() {
        if (running) stop()
        super.doCancelAction()
    }

    override fun dispose() {
        if (running) stop()
        super.dispose()
    }

    private fun createHeader(): JComponent {
        val heading = JBLabel("Rebase on develop", MphIcons.SyncDevelop, JBLabel.LEFT)
        heading.font = heading.font.deriveFont(Font.BOLD, heading.font.size2D + 3f)
        val explanation = JBTextArea("Prefix '${plan.prefix}' will be reapplied and all dependents aligned only when every repository succeeds.", 2, 45).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = heading.font.deriveFont(heading.font.size2D - 3f)
        }
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
        operation = try {
            WorkspaceOperationCoordinator.acquire("Synchronization")
        } catch (error: IllegalStateException) {
            statusLabel.text = error.message
            return
        }
        val documents = FileDocumentManager.getInstance()
        try {
            documents.saveAllDocuments()
        } catch (error: Exception) {
            finishOperation()
            statusLabel.text = "Could not save editor changes: ${error.message}"
            return
        }
        if (documents.unsavedDocuments.isNotEmpty()) {
            finishOperation()
            statusLabel.text = "Save all editor changes before synchronizing."
            return
        }
        stopRequested = false
        running = true
        resolveButton.isEnabled = false
        startButton.isEnabled = false
        stopButton.isEnabled = true
        statusLabel.text = "Rebasing repositories sequentially…"
        plan.repositories.indices.forEach { updateRow(it, GitRebaseStatus.PENDING, "Waiting") }
        try {
            SynchronizeTask().queue()
        } catch (error: Exception) {
            finishOperation()
            statusLabel.text = "Could not start synchronization: ${error.message}"
        }
    }

    private inner class SynchronizeTask : Task.Backgroundable(
        ideProject,
        "Synchronizing repositories with develop",
        true,
    ) {
        private var allSucceeded = false
        private var cancelled = false

        override fun run(indicator: ProgressIndicator) {
            gitIndicator = indicator
            if (stopRequested) indicator.cancel()
            val listener = GitRebaseListener { repository, status, message ->
                ApplicationManager.getApplication().invokeLater {
                    updateRepositoryRow(repository, status, message)
                }
            }
            val results = gitService.rebase(plan, indicator, listener, operation)
            results.forEach { result ->
                ApplicationManager.getApplication().invokeLater {
                    updateRepositoryRow(result.repository, result.status, result.message, result.recoveryHint)
                }
            }
            cancelled = indicator.isCanceled || results.any { it.status == GitRebaseStatus.CANCELLED }
            allSucceeded = !cancelled && results.isNotEmpty() && results.all { it.status == GitRebaseStatus.SUCCESS }
            if (allSucceeded) expectedRepositories = plan.repositories.map { GitWorkspaceFingerprint.capture(Path.of(it.rootPath)) }
        }

        override fun onSuccess() {
            if (allSucceeded && !stopRequested && !isDisposed) {
                refreshAndAlign()
            } else {
                statusLabel.text = if (cancelled || stopRequested) {
                    "Cancelled. Version alignment was skipped."
                } else {
                    "Finished with issues. Resolve them before aligning versions."
                }
            }
        }

        override fun onCancel() {
            statusLabel.text = "Cancelled. Version alignment was skipped. Recovery backups retained."
        }

        override fun onThrowable(error: Throwable) {
            statusLabel.text = "Synchronization stopped: ${error.message}. Inspect Git status and .git/mph-recovery."
        }

        override fun onFinished() {
            gitIndicator = null
            if (!refreshing) finishOperation()
        }
    }

    private fun updateRepositoryRow(repository: GitRepositoryPlan, status: GitRebaseStatus, message: String, recoveryHint: String? = null) {
        val index = plan.repositories.indexOf(repository)
        if (index >= 0) updateRow(index, status, message, recoveryHint)
    }

    private fun finishOperation() {
        operation?.close()
        operation = null
        refreshing = false
        running = false
        startButton.isEnabled = true
        stopButton.isEnabled = false
        resolveButton.isEnabled = repositoryList.selectedValue?.status == GitRebaseStatus.CONFLICT
    }

    internal fun refreshAndAlign() {
        if (operation == null) operation = WorkspaceOperationCoordinator.acquire("Synchronization")
        running = true
        refreshing = true
        statusLabel.text = "Refreshing Maven projects before version alignment…"
        val owner = operation
        val success = {
            if (operation === owner) {
                try {
                    if (stopRequested || isDisposed || ideProject.isDisposed) {
                        statusLabel.text = "Cancelled. Version alignment was skipped."
                    } else {
                        verifyRepositories()
                        val fresh = discover?.invoke() ?: ideProject.service<IdeaProjectDiscoveryService>()
                            .discover().groups.flatMap { it.projects }
                        val roots = plan.repositories.map { Path.of(it.rootPath).toAbsolutePath().normalize() }.toSet()
                        val selected = fresh.filter { it.gitRootPath?.let { root -> Path.of(root).toAbsolutePath().normalize() } in roots }
                        check(roots.all { root -> selected.any { Path.of(it.gitRootPath!!).toAbsolutePath().normalize() == root } }) {
                            "Maven refresh did not find projects in every synchronized repository."
                        }
                        if (FileDocumentManager.getInstance().unsavedDocuments.isNotEmpty()) {
                            statusLabel.text = "Editor changes appeared during synchronization. Version alignment was skipped."
                        } else if (align != null) {
                            align.invoke(selected, fresh)
                        } else {
                            alignVersions(selected, fresh)
                        }
                    }
                } catch (error: Exception) {
                    statusLabel.text = "Version alignment skipped: ${error.message}"
                } finally {
                    finishOperation()
                }
            }
        }
        val failure: (Throwable) -> Unit = { error ->
            if (operation === owner) {
                statusLabel.text = "Maven refresh failed. Version alignment was skipped: ${error.message}"
                finishOperation()
            }
        }
        try {
            if (reloadMaven != null) reloadMaven.invoke(success, failure)
            else {
                plan.repositories.forEach { repository ->
                    LocalFileSystem.getInstance().refreshAndFindFileByPath(repository.rootPath)?.refresh(false, true)
                }
                ideProject.service<MavenModelRefreshService>().reload(success, failure)
            }
        } catch (error: Exception) {
            failure(error)
        }
    }

    private fun alignVersions(selectedProjects: List<MavenProjectInfo>, workspaceProjects: List<MavenProjectInfo>) {
        if (FileDocumentManager.getInstance().unsavedDocuments.isNotEmpty()) {
            statusLabel.text = "Editor changes appeared during synchronization. Version alignment was skipped."
            return
        }
        plan.repositories.forEach { repository ->
            LocalFileSystem.getInstance().refreshAndFindFileByPath(repository.rootPath)?.refresh(false, true)
        }
        val recovery = try {
            GitRecoverySnapshots.capture(
                Path.of(plan.repositories.first().rootPath).resolve(".git"),
                workspaceProjects.map { Path.of(it.pomPath) },
            )
        } catch (error: Exception) {
            statusLabel.text = "Version alignment skipped: recovery copies could not be saved (${error.message})."
            return
        }
        statusLabel.text = "Reapplying '${plan.prefix}' and aligning dependent versions…"
        val alignment = try {
            reviewVersionAlignment(ideProject,
                BulkVersionUpdateRequest(
                    selectedProjects = selectedProjects,
                    workspaceProjects = workspaceProjects,
                    prefix = plan.prefix,
                    mode = BulkVersionMode.ADD_PREFIX,
                    updateDependents = true,
                    normalizePrefix = true,
                ),
                operation,
                validateState = {
                    verifyRepositories()
                    val current = ideProject.service<IdeaProjectDiscoveryService>().discover().groups.flatMap { it.projects }
                    check(current.map { it.copy(gitStatus = null) }.toSet() == workspaceProjects.map { it.copy(gitStatus = null) }.toSet()) {
                        "The Maven model changed after synchronization. Refresh and review alignment again."
                    }
                },
            )
        } catch (error: Exception) {
            statusLabel.text = "Version alignment stopped. Recovery copies were retained."
            recoveryDetails.text = "${error.message}\n\nPre-alignment POM copies: $recovery"
            return
        }
        if (alignment == null) {
            statusLabel.text = "Git synchronization completed. Version alignment was not applied."
            return
        }
        recoveryDetails.text = "Pre-alignment POM copies: $recovery\n\n" + alignment.issues.joinToString("\n")
        statusLabel.text = if (alignment.issues.isEmpty()) {
            "Completed. Git and ${alignment.updatedProjectCount} project version(s) are aligned. Recovery copies retained."
        } else {
            "Completed with ${alignment.issues.size} version-alignment warning(s). Recovery copies retained."
        }
    }

    private fun verifyRepositories() {
        if (expectedRepositories.isEmpty()) return
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            { expectedRepositories.forEach(GitWorkspaceFingerprint::verify) },
            "Checking repository changes", false, ideProject,
        )
    }

    private fun stop() {
        stopRequested = true
        gitIndicator?.cancel()
        statusLabel.text = if (refreshing) "Stopping after Maven refresh; alignment will be skipped…" else "Stopping after the active Git command…"
        stopButton.isEnabled = false
    }

    internal fun recordRepositoryResult(result: GitRepositoryResult) =
        updateRepositoryRow(result.repository, result.status, result.message, result.recoveryHint)

    internal fun resolveSelectedConflicts() {
        val row = repositoryList.selectedValue ?: return
        if (running || row.status != GitRebaseStatus.CONFLICT) return
        resolveButton.isEnabled = false
        try {
            val message = (conflictResolver ?: ideProject.service<IdeaConflictResolutionService>()::resolve)(row.repository.rootPath)
            statusLabel.text = message
            recoveryDetails.text = listOfNotNull(row.recoveryHint, message).joinToString("\n\n")
        } catch (error: Exception) {
            statusLabel.text = "Conflict resolution stopped: ${error.message}"
        } finally {
            resolveButton.isEnabled = !running
        }
    }

    private fun updateRow(index: Int, status: GitRebaseStatus, message: String, recoveryHint: String? = null) {
        val row = listModel.get(index)
        listModel.set(index, row.copy(status = status, message = message, recoveryHint = recoveryHint))
        showRecoveryDetails()
    }

    private fun showRecoveryDetails() {
        val row = repositoryList.selectedValue
        resolveButton.isEnabled = !running && row?.status == GitRebaseStatus.CONFLICT
        recoveryDetails.text = row?.let { listOfNotNull(it.repository.rootPath, it.message, it.recoveryHint).joinToString("\n\n") }.orEmpty()
        recoveryDetails.caretPosition = 0
    }
}

private data class GitRebaseRow(
    val repository: GitRepositoryPlan,
    val status: GitRebaseStatus,
    val message: String,
    val recoveryHint: String? = null,
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
            GitRebaseStatus.PENDING -> MphIcons.Waiting
            GitRebaseStatus.RUNNING -> MphIcons.Running
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
        toolTipText = "${value.repository.rootPath}: ${value.message}"
    }
}
