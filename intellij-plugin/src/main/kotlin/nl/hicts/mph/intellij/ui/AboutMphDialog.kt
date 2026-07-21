package nl.hicts.mph.intellij.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import nl.hicts.mph.intellij.icons.MphIcons
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

internal object MphProjectInfo {
    const val PLUGIN_ID = "nl.hicts.mph.plugin"
    const val PROJECT_URL = "https://github.com/mrhoeve/mph"
    const val ISSUES_URL = "$PROJECT_URL/issues"
}

internal class AboutMphDialog(project: Project) : DialogWrapper(project) {
    init {
        title = "About Maven Project Helper"
        setOKButtonText("Close")
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val installedVersion = PluginManagerCore.getPlugin(PluginId.getId(MphProjectInfo.PLUGIN_ID))
            ?.version ?: "Development build"

        val heading = JBLabel("Maven Project Helper", MphIcons.Mph, JBLabel.LEFT).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 5f)
            iconTextGap = JBUI.scale(12)
        }
        val version = JBLabel("Version $installedVersion").apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
            border = JBUI.Borders.emptyLeft(30)
        }
        val header = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = false
            add(heading, BorderLayout.CENTER)
            add(version, BorderLayout.SOUTH)
        }
        val description = JBLabel(
            "<html>Manage versions, builds, dependencies, security analysis, and Git synchronization " +
                "across an entire Maven workspace directly from IntelliJ IDEA.</html>",
        ).apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }

        val details = JPanel(GridBagLayout()).apply { isOpaque = false }
        addDetail(details, 0, "Maintainer", JBLabel("Marc Hoeve"))
        addDetail(details, 1, "License", JBLabel("MIT License"))
        addDetail(
            details,
            2,
            "Source",
            ActionLink("github.com/mrhoeve/mph") { BrowserUtil.browse(MphProjectInfo.PROJECT_URL) },
        )

        val links = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(14), 0)).apply {
            isOpaque = false
            add(ActionLink("View on GitHub") { BrowserUtil.browse(MphProjectInfo.PROJECT_URL) })
            add(ActionLink("Report an issue") { BrowserUtil.browse(MphProjectInfo.ISSUES_URL) })
        }
        val credits = JBLabel(
            "<html>Developed with the assistance of <b>OpenAI Codex</b> and <b>JetBrains Junie</b>.<br>" +
                "Thank you to everyone who helps improve Maven Project Helper.</html>",
        ).apply {
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }

        return JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 10, 4, 10)
            preferredSize = Dimension(JBUI.scale(520), JBUI.scale(300))
            listOf<JComponent>(header, description).forEach {
                it.alignmentX = JComponent.LEFT_ALIGNMENT
                add(it)
                add(javax.swing.Box.createVerticalStrut(JBUI.scale(12)))
            }
            add(TitledSeparator("Project details").apply { alignmentX = JComponent.LEFT_ALIGNMENT })
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
            add(details.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(10)))
            add(links.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(12)))
            add(TitledSeparator("Credits").apply { alignmentX = JComponent.LEFT_ALIGNMENT })
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
            add(credits.apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        }
    }

    private fun addDetail(panel: JPanel, row: Int, label: String, value: JComponent) {
        val labelConstraints = GridBagConstraints().apply {
            gridx = 0
            gridy = row
            anchor = GridBagConstraints.NORTHWEST
            insets = Insets(JBUI.scale(3), 0, JBUI.scale(3), JBUI.scale(18))
        }
        val valueConstraints = GridBagConstraints().apply {
            gridx = 1
            gridy = row
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
            insets = Insets(JBUI.scale(3), 0, JBUI.scale(3), 0)
        }
        panel.add(JBLabel(label).apply { foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND }, labelConstraints)
        panel.add(value, valueConstraints)
    }
}
