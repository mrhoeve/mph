package nl.hicts.mph.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.services.GitWorkspaceService
import nl.hicts.mph.intellij.services.LatestTagVersion
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class ProjectVersionUpdateDialog(
    private val ideProject: Project,
    private val projectInfo: MavenProjectInfo,
) : DialogWrapper(ideProject) {
    private val versionField = JBTextField(projectInfo.version.orEmpty())
    private val latestTag = JButton("Use latest Git tag", AllIcons.Vcs.Branch)
    private val tagStatus = JBLabel(" ")

    val selectedVersion: String get() = versionField.text.trim()

    init {
        title = "Update Project and Dependent Versions"
        setOKButtonText("Update Versions")
        latestTag.isEnabled = projectInfo.gitRootPath != null
        latestTag.addActionListener { findLatestTag() }
        tagStatus.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        init()
    }

    override fun createCenterPanel(): JComponent {
        val tagPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(latestTag)
        }
        return JPanel(BorderLayout(0, JBUI.scale(12))).apply {
            border = JBUI.Borders.empty(8, 4, 4, 4)
            add(JBLabel("Update ${projectInfo.artifactId}, its modules, and all linked usages."), BorderLayout.NORTH)
            add(FormBuilder.createFormBuilder()
                .addLabeledComponent("Target version:", versionField, 1, false)
                .addComponent(tagPanel)
                .addComponent(tagStatus)
                .panel, BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(560), JBUI.scale(190))
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = versionField

    override fun doValidate(): ValidationInfo? = when {
        selectedVersion.isBlank() -> ValidationInfo("Enter the target Maven version.", versionField)
        selectedVersion.any { it == '<' || it == '>' || it == '&' } ->
            ValidationInfo("The version contains characters that are unsafe in XML.", versionField)
        else -> null
    }

    private fun findLatestTag() {
        latestTag.isEnabled = false
        tagStatus.text = "Fetching Git tags…"
        object : Task.Backgroundable(ideProject, "Finding latest Maven version", true) {
            private var result: LatestTagVersion? = null
            override fun run(indicator: ProgressIndicator) {
                result = ideProject.service<GitWorkspaceService>().latestVersion(projectInfo)
            }

            override fun onSuccess() {
                val found = result
                if (found == null) tagStatus.text = "No tag containing this pom.xml was found."
                else {
                    versionField.text = found.version
                    tagStatus.text = "${found.version} from ${found.tagName}"
                }
                latestTag.isEnabled = true
            }

            override fun onThrowable(error: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    tagStatus.text = "Could not retrieve tags: ${error.message ?: error.javaClass.simpleName}"
                    latestTag.isEnabled = true
                }
            }
        }.queue()
    }
}
