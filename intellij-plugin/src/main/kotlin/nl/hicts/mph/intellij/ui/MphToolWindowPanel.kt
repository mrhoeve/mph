package nl.hicts.mph.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import nl.hicts.mph.intellij.icons.MphIcons
import nl.hicts.mph.intellij.model.GitProjectGroup
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.model.ProjectSnapshot
import nl.hicts.mph.intellij.services.IdeaProjectDiscoveryService
import nl.hicts.mph.intellij.services.BulkVersionUpdateRequest
import nl.hicts.mph.intellij.services.BulkVersionUpdateService
import nl.hicts.mph.intellij.services.GitRebaseService
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.Font
import java.nio.file.Path
import javax.swing.JPanel
import javax.swing.JComponent
import javax.swing.SwingConstants
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class MphToolWindowPanel(
    private val project: Project,
    private val discoverProjects: () -> ProjectSnapshot = {
        project.service<IdeaProjectDiscoveryService>().discover()
    },
    private val openPom: (String) -> Unit = { pomPath ->
        LocalFileSystem.getInstance().refreshAndFindFileByPath(pomPath)?.let { virtualFile ->
            OpenFileDescriptor(project, virtualFile).navigate(true)
        }
    },
    refreshOnCreate: Boolean = true,
) : SimpleToolWindowPanel(true, true), Disposable {
    private val summary = JBLabel("Discovering Maven projects…", SwingConstants.LEFT)
    private val rootNode = DefaultMutableTreeNode("Maven Projects")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)
    private var snapshot = ProjectSnapshot(emptyList())

    init {
        val refreshAction = object : DumbAwareAction("Refresh", "Refresh Maven projects", null) {
            override fun actionPerformed(event: AnActionEvent) = refresh()
        }
        val alignVersionsAction = object : DumbAwareAction(
            "Align Versions",
            "Add or remove a version prefix across the selected Maven projects",
            AllIcons.Actions.Edit,
        ) {
            override fun actionPerformed(event: AnActionEvent) = alignSelectedVersions()

            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = selectedProjects().isNotEmpty()
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        val buildAction = object : DumbAwareAction(
            "Build",
            "Run Maven for the selected projects",
            AllIcons.Actions.Compile,
        ) {
            override fun actionPerformed(event: AnActionEvent) {
                val selected = selectedBuildProjects()
                if (selected.isNotEmpty()) MavenBuildDialog(project, selected).show()
            }

            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = selectedBuildProjects().isNotEmpty()
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        val rebaseAction = object : DumbAwareAction(
            "Sync with develop",
            "Stash changes, rebase selected repositories on develop, restore work, and realign versions",
            AllIcons.Vcs.Branch,
        ) {
            override fun actionPerformed(event: AnActionEvent) = openRebaseDialog()

            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = selectedProjects().any { !it.gitRootPath.isNullOrBlank() }
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }
        val toolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.TOOLWINDOW_TOOLBAR_BAR,
            DefaultActionGroup(alignVersionsAction, buildAction, rebaseAction, refreshAction),
            true,
        )
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = javax.swing.tree.TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        tree.emptyText.text = "No linked Maven projects"
        tree.cellRenderer = MphProjectTreeRenderer()
        TreeSpeedSearch.installOn(tree, true) { path -> path.lastPathComponent.toString() }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2 && event.button == MouseEvent.BUTTON1) {
                    openSelectedPom()
                }
            }
        })

        val body = JPanel(BorderLayout())
        body.add(createHeader(), BorderLayout.NORTH)
        body.add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        setContent(body)

        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (containsPomChange(events.map(VFileEvent::getPath))) {
                        ApplicationManager.getApplication().invokeLater(::refresh)
                    }
                }
            },
        )

        if (refreshOnCreate) refresh()
    }

    internal val summaryText: String
        get() = summary.text

    internal val projectTree: Tree
        get() = tree

    internal fun containsPomChange(paths: List<String>): Boolean =
        paths.any { it.endsWith("/pom.xml") || it.endsWith("\\pom.xml") }

    internal fun refresh() {
        if (project.isDisposed) return
        summary.text = "Refreshing Maven projects…"

        createRefreshTask().queue()
    }

    internal fun createRefreshTask(): Task.Backgroundable =
        object : Task.Backgroundable(project, "Refreshing Maven Project Helper", false) {
            private lateinit var snapshot: ProjectSnapshot

            override fun run(indicator: ProgressIndicator) {
                snapshot = discoverProjects()
            }

            override fun onSuccess() {
                render(snapshot)
            }

            override fun onThrowable(error: Throwable) {
                summary.text = "Unable to discover Maven projects: ${error.message ?: error.javaClass.simpleName}"
            }
        }

    internal fun render(snapshot: ProjectSnapshot) {
        this.snapshot = snapshot
        rootNode.removeAllChildren()
        snapshot.groups.forEach { group -> rootNode.add(groupNode(group)) }
        treeModel.reload()
        expandAllRows()
        summary.text = "${snapshot.projectCount} Maven projects in ${snapshot.repositoryCount} Git repositories"
    }

    private fun groupNode(group: GitProjectGroup): DefaultMutableTreeNode {
        val label = group.rootPath?.let { Path.of(it).fileName?.toString() ?: it } ?: "Outside a Git repository"
        val repository = RepositoryTreeEntry(label, group.rootPath, group.projects)
        return DefaultMutableTreeNode(repository).also { node ->
            group.projects.forEach { node.add(DefaultMutableTreeNode(ProjectTreeEntry(it))) }
        }
    }

    private fun createHeader(): JComponent {
        val title = JBLabel("Maven Project Helper", MphIcons.Mph, SwingConstants.LEFT)
        title.font = title.font.deriveFont(Font.BOLD, title.font.size2D + 2f)
        title.iconTextGap = JBUI.scale(10)

        summary.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        summary.border = JBUI.Borders.emptyLeft(50)

        return JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            border = JBUI.Borders.empty(10, 12, 10, 12)
            add(title, BorderLayout.CENTER)
            add(summary, BorderLayout.SOUTH)
        }
    }

    private fun expandAllRows() {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row++)
        }
    }

    internal fun openSelectedPom() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val projectInfo = (node.userObject as? ProjectTreeEntry)?.project ?: return
        openPom(projectInfo.pomPath)
    }

    internal fun selectedProjects(): List<MavenProjectInfo> = tree.selectionPaths.orEmpty()
        .flatMap { path ->
            when (val entry = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
                is ProjectTreeEntry -> listOf(entry.project)
                is RepositoryTreeEntry -> entry.projects
                else -> emptyList()
            }
        }
        .distinctBy(MavenProjectInfo::pomPath)

    internal fun selectedBuildProjects(): List<MavenProjectInfo> = tree.selectionPaths.orEmpty()
        .flatMap { path ->
            when (val entry = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
                is ProjectTreeEntry -> listOf(entry.project)
                is RepositoryTreeEntry -> listOfNotNull(entry.rootProject())
                else -> emptyList()
            }
        }
        .distinctBy(MavenProjectInfo::pomPath)

    private fun alignSelectedVersions() {
        val selected = selectedProjects()
        if (selected.isEmpty()) return
        val dialog = BulkVersionUpdateDialog(project, selected)
        if (!dialog.showAndGet()) return

        val result = project.service<BulkVersionUpdateService>().update(
            BulkVersionUpdateRequest(
                selectedProjects = selected,
                workspaceProjects = snapshot.groups.flatMap(GitProjectGroup::projects),
                prefix = dialog.prefix,
                mode = dialog.mode,
                updateDependents = dialog.updateDependents,
            ),
        )
        val summary = buildString {
            append("Updated ${result.updatedProjectCount} project")
            if (result.updatedProjectCount != 1) append('s')
            append(" and ${result.updatedReferenceCount} dependent reference")
            if (result.updatedReferenceCount != 1) append('s')
            append(". Changes remain uncommitted and can be undone together.")
            if (result.issues.isNotEmpty()) {
                append("<br><br>")
                append(result.issues.joinToString("<br>") { issue -> "• $issue" })
            }
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Maven Project Helper")
            .createNotification(
                "Maven version alignment completed",
                summary,
                if (result.issues.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING,
            )
            .notify(project)
    }

    private fun openRebaseDialog() {
        val selected = selectedProjects()
        if (selected.isEmpty()) return
        val workspaceProjects = snapshot.groups.flatMap(GitProjectGroup::projects)
        val plan = try {
            project.service<GitRebaseService>().createPlan(selected, workspaceProjects)
        } catch (error: IllegalArgumentException) {
            Messages.showErrorDialog(
                project,
                error.message ?: "The selected repositories cannot be rebased safely.",
                "Cannot Synchronize Repositories",
            )
            return
        }
        GitRebaseDialog(project, plan, workspaceProjects).show()
    }

    override fun dispose() = Unit
}

internal data class ProjectTreeEntry(
    val project: MavenProjectInfo,
) {
    override fun toString(): String = buildString {
        append(project.artifactId)
        project.version?.takeIf(String::isNotBlank)?.let { append("  ").append(it) }
        project.groupId?.takeIf(String::isNotBlank)?.let { append("  (").append(it).append(')') }
    }
}

internal data class RepositoryTreeEntry(
    val label: String,
    val rootPath: String?,
    val projects: List<MavenProjectInfo>,
) {
    val projectCount: Int
        get() = projects.size

    fun rootProject(): MavenProjectInfo? {
        val root = rootPath?.let(Path::of)?.toAbsolutePath()?.normalize()
        return projects.minByOrNull { project ->
            val parent = Path.of(project.pomPath).toAbsolutePath().normalize().parent
            if (root != null && parent.startsWith(root)) root.relativize(parent).nameCount else parent.nameCount
        }
    }

    override fun toString(): String = label
}

internal class MphProjectTreeRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        when (val entry = (value as? DefaultMutableTreeNode)?.userObject) {
            is RepositoryTreeEntry -> {
                icon = AllIcons.Nodes.Folder
                append(entry.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${entry.projectCount}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = entry.rootPath
            }

            is ProjectTreeEntry -> {
                icon = AllIcons.Nodes.Module
                append(entry.project.artifactId, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                entry.project.version?.takeIf(String::isNotBlank)?.let {
                    append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                entry.project.groupId?.takeIf(String::isNotBlank)?.let {
                    append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                toolTipText = entry.project.pomPath
            }

            else -> append(entry?.toString().orEmpty())
        }
    }
}
