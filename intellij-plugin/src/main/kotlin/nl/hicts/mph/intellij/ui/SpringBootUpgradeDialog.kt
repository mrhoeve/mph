package nl.hicts.mph.intellij.ui

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import nl.hicts.mph.intellij.services.SpringBootVersionCatalog
import nl.hicts.mph.intellij.services.SpringBootVersionReference
import nl.hicts.mph.intellij.services.SpringBootVersions
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JComboBox

class SpringBootUpgradeDialog(
    private val ideProject: Project,
    private val reference: SpringBootVersionReference,
) : DialogWrapper(ideProject) {
    private val versions = DefaultComboBoxModel(arrayOf(reference.currentVersion))
    private val versionField = JComboBox(versions).apply { isEditable = true }
    private val status = JBLabel("Loading stable Spring Boot versions…")

    val selectedVersion: String
        get() = versionField.editor.item?.toString()?.trim().orEmpty()

    init {
        title = "Upgrade Spring Boot"
        setOKButtonText("Update pom.xml")
        init()
        loadVersions()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Current version:", JBLabel(reference.currentVersion), 1, false)
        .addLabeledComponent("Target version:", versionField, 1, false)
        .addComponent(status)
        .panel

    override fun doValidate(): ValidationInfo? = when {
        selectedVersion.isBlank() -> ValidationInfo("Select or enter a Spring Boot version.", versionField)
        selectedVersion == reference.currentVersion -> ValidationInfo("Choose a version different from the current version.", versionField)
        selectedVersion.any { it == '<' || it == '>' || it == '&' } -> ValidationInfo("The version is unsafe in XML.", versionField)
        else -> null
    }

    private fun loadVersions() {
        object : Task.Backgroundable(ideProject, "Finding Spring Boot versions", true) {
            private var newer = emptyList<String>()
            override fun run(indicator: ProgressIndicator) {
                newer = SpringBootVersions.stableNewerThan(reference.currentVersion, SpringBootVersionCatalog.fetchStableVersions())
            }

            override fun onSuccess() {
                newer.take(20).forEach(versions::addElement)
                if (newer.isNotEmpty()) {
                    versionField.selectedItem = newer.first()
                    status.text = "Latest stable: ${newer.first()}"
                } else {
                    status.text = "No newer stable version found; you can enter one manually."
                }
            }

            override fun onThrowable(error: Throwable) {
                status.text = "Version lookup unavailable; enter the target version manually."
            }
        }.queue()
    }
}
