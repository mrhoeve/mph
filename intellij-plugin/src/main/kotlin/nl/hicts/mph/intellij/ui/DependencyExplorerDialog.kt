package nl.hicts.mph.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import nl.hicts.mph.intellij.model.WorkspaceProjectRelationship
import nl.hicts.mph.intellij.model.WorkspaceProjectRelationships
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class DependencyExplorerDialog(
    project: Project,
    private val relationships: WorkspaceProjectRelationships,
    private val openPom: (String) -> Unit,
) : DialogWrapper(project) {
    private val root = DefaultMutableTreeNode(relationships.project.artifactId)
    private val tree = Tree(DefaultTreeModel(root))

    init {
        title = "Maven Dependency Explorer"
        createNodes()
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = RelationshipRenderer()
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 2 || event.button != MouseEvent.BUTTON1) return
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                val relationship = node.userObject as? WorkspaceProjectRelationship ?: return
                relationship.project?.pomPath?.let(openPom)
            }
        })
        init()
    }

    override fun createCenterPanel(): JComponent {
        val titleLabel = JBLabel(relationships.project.artifactId, AllIcons.Nodes.Module, JBLabel.LEFT)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, titleLabel.font.size2D + 3f)
        val summary = JBLabel(
            "${relationships.dependencies.size} dependencies · ${relationships.dependents.size} workspace dependents",
        )
        summary.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        val header = javax.swing.JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.NORTH)
            add(summary, BorderLayout.SOUTH)
        }
        expandAll()
        return javax.swing.JPanel(BorderLayout(0, JBUI.scale(12))).apply {
            border = JBUI.Borders.empty(8, 4, 4, 4)
            add(header, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(760), JBUI.scale(520))
        }
    }

    private fun createNodes() {
        val dependencies = DefaultMutableTreeNode(RelationshipGroup("Depends on", relationships.dependencies.size))
        relationships.dependencies.forEach { dependencies.add(DefaultMutableTreeNode(it)) }
        val dependents = DefaultMutableTreeNode(RelationshipGroup("Used by", relationships.dependents.size))
        relationships.dependents.forEach { dependents.add(DefaultMutableTreeNode(it)) }
        root.add(dependencies)
        root.add(dependents)
    }

    private fun expandAll() {
        var row = 0
        while (row < tree.rowCount) tree.expandRow(row++)
    }
}

private data class RelationshipGroup(val label: String, val count: Int)

private class RelationshipRenderer : ColoredTreeCellRenderer() {
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
            is RelationshipGroup -> {
                icon = if (entry.label == "Depends on") AllIcons.Actions.Download else AllIcons.Actions.Upload
                append(entry.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${entry.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is WorkspaceProjectRelationship -> {
                icon = if (entry.project == null) AllIcons.Nodes.PpLib else AllIcons.Nodes.Module
                append(entry.coordinates.artifactId, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${entry.coordinates.groupId}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append("  ${entry.kinds.joinToString { it.displayName }}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                toolTipText = entry.project?.pomPath ?: "External Maven component"
            }
        }
    }
}
