package nl.hicts.mph.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import nl.hicts.mph.intellij.services.CycloneDxExporter
import nl.hicts.mph.intellij.services.SbomAnalysis
import nl.hicts.mph.intellij.services.SbomComponent
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class SbomDialog(
    private val ideaProject: Project,
    private val analysis: SbomAnalysis,
) : DialogWrapper(ideaProject) {
    private val root = DefaultMutableTreeNode(analysis.project.artifactId)
    internal val dependencyTree = Tree(DefaultTreeModel(root))

    init {
        title = "Software Bill of Materials"
        analysis.dependencies.forEach { root.add(componentNode(it)) }
        dependencyTree.isRootVisible = false
        dependencyTree.showsRootHandles = true
        dependencyTree.emptyText.text = if (analysis.dependencies.isEmpty()) {
            "No dependencies found for this Maven project."
        } else {
            ""
        }
        dependencyTree.cellRenderer = SbomRenderer()
        init()
    }

    override fun createCenterPanel(): JComponent {
        val titleLabel = JBLabel(analysis.project.artifactId, AllIcons.Nodes.Module, JBLabel.LEFT).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 3f)
        }
        val unresolved = analysis.components.count { !it.resolved }
        val summary = JBLabel(
            "${analysis.components.size} components · ${analysis.dependencies.size} direct" +
                if (unresolved == 0) " · all resolved" else " · $unresolved unresolved",
        ).apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }
        val header = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.NORTH)
            add(summary, BorderLayout.SOUTH)
        }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JButton("Export CycloneDX JSON…", AllIcons.ToolbarDecorator.Export).apply {
                addActionListener { export("json", CycloneDxExporter.json(analysis)) }
            })
            add(JButton("Export CycloneDX XML…", AllIcons.ToolbarDecorator.Export).apply {
                addActionListener { export("xml", CycloneDxExporter.xml(analysis)) }
            })
        }
        expandDirectDependencies()
        return JPanel(BorderLayout(0, JBUI.scale(12))).apply {
            border = JBUI.Borders.empty(8, 4, 4, 4)
            add(header, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(dependencyTree, true), BorderLayout.CENTER)
            add(actions, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(820), JBUI.scale(560))
        }
    }

    private fun componentNode(component: SbomComponent): DefaultMutableTreeNode =
        DefaultMutableTreeNode(component).also { node ->
            component.children.forEach { node.add(componentNode(it)) }
        }

    private fun expandDirectDependencies() {
        dependencyTree.expandPath(TreePath(root.path))
        repeat(root.childCount) { index ->
            val child = root.getChildAt(index) as DefaultMutableTreeNode
            dependencyTree.expandPath(TreePath(child.path))
        }
    }

    private fun export(extension: String, content: String) {
        val descriptor = FileSaverDescriptor("Export CycloneDX SBOM", "Save the dependency inventory", extension)
        val defaultName = "${analysis.project.artifactId}-${analysis.project.version.orEmpty()}-sbom.$extension"
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, ideaProject).save(defaultName)
            ?: return
        Files.writeString(target.file.toPath(), content)
    }
}

private class SbomRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val component = (value as? DefaultMutableTreeNode)?.userObject as? SbomComponent ?: return
        icon = if (component.resolved) AllIcons.Nodes.PpLib else AllIcons.General.Warning
        append(component.artifactId, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        append("  ${component.version}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append("  ${component.scope}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        if (!component.resolved) append("  unresolved", SimpleTextAttributes.ERROR_ATTRIBUTES)
        toolTipText = component.coordinates
    }
}
