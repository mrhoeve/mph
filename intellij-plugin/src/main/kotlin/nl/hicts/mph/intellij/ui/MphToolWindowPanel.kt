package nl.hicts.mph.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
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

    init {
        val refreshAction = object : DumbAwareAction("Refresh", "Refresh Maven projects", null) {
            override fun actionPerformed(event: AnActionEvent) = refresh()
        }
        val toolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.TOOLWINDOW_TOOLBAR_BAR,
            DefaultActionGroup(refreshAction),
            true,
        )
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION
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
        rootNode.removeAllChildren()
        snapshot.groups.forEach { group -> rootNode.add(groupNode(group)) }
        treeModel.reload()
        expandAllRows()
        summary.text = "${snapshot.projectCount} Maven projects in ${snapshot.repositoryCount} Git repositories"
    }

    private fun groupNode(group: GitProjectGroup): DefaultMutableTreeNode {
        val label = group.rootPath?.let { Path.of(it).fileName?.toString() ?: it } ?: "Outside a Git repository"
        val repository = RepositoryTreeEntry(label, group.rootPath, group.projects.size)
        return DefaultMutableTreeNode(repository).also { node ->
            group.projects.forEach { node.add(DefaultMutableTreeNode(ProjectTreeEntry(it))) }
        }
    }

    private fun createHeader(): JComponent {
        val title = JBLabel("Maven Project Helper", MphIcons.Plugin, SwingConstants.LEFT)
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
    val projectCount: Int,
) {
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
