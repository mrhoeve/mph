package nl.hicts.mph.intellij.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import nl.hicts.mph.intellij.services.ManagedVersionProperty
import javax.swing.JComponent

class PropertyOverrideDialog(
    project: Project,
    property: ManagedVersionProperty,
) : DialogWrapper(project) {
    private val valueField = JBTextField(property.value)
    private val commentField = JBTextField(property.comment.orEmpty())
    val value: String
        get() = valueField.text.trim()
    val comment: String?
        get() = commentField.text.trim().takeIf(String::isNotBlank)

    init {
        title = "Override ${property.name}"
        setOKButtonText("Apply Override")
        commentField.emptyText.text = "Optional explanation placed above the property"
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("New value:", valueField, 1, false)
        .addLabeledComponent("Comment:", commentField, 1, false)
        .panel

    override fun getPreferredFocusedComponent(): JComponent = valueField

    override fun doValidate(): ValidationInfo? = when {
        value.isBlank() -> ValidationInfo("Enter a property value.", valueField)
        value.any { it == '<' || it == '>' || it == '&' } -> ValidationInfo("The value is unsafe in XML.", valueField)
        comment?.contains("--") == true -> ValidationInfo("An XML comment cannot contain '--'.", commentField)
        else -> null
    }
}
