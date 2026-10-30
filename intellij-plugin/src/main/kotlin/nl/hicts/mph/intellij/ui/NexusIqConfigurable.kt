package nl.hicts.mph.intellij.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import nl.hicts.mph.intellij.services.NexusIqSettings
import javax.swing.JComponent

class NexusIqConfigurable : Configurable {
    private val serverUrl = JBTextField()
    private val username = JBTextField()
    private val password = JBPasswordField()
    private val prefix = JBTextField()
    private val suffix = JBTextField()
    private var panel: JComponent? = null

    override fun getDisplayName() = "Maven Project Helper"

    override fun createComponent(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Nexus IQ server URL:", serverUrl)
        .addLabeledComponent("Username:", username)
        .addLabeledComponent("Password / token:", password)
        .addLabeledComponent("Application ID prefix:", prefix)
        .addLabeledComponent("Application ID suffix:", suffix)
        .addComponentFillVertically(javax.swing.JPanel(), 0)
        .panel.also { panel = it }

    override fun isModified(): Boolean {
        val settings = service<NexusIqSettings>()
        val state = settings.state
        return serverUrl.text.trim().trimEnd('/') != state.serverUrl ||
            username.text.trim() != state.username ||
            String(password.password) != settings.password.orEmpty() ||
            prefix.text.trim() != state.applicationIdPrefix ||
            suffix.text.trim() != state.applicationIdSuffix
    }

    override fun apply() {
        val url = serverUrl.text.trim()
        if (url.isNotEmpty() && !url.startsWith("https://") && !url.startsWith("http://")) {
            throw ConfigurationException("Enter a complete Nexus IQ URL beginning with https:// or http://.")
        }
        service<NexusIqSettings>().update(
            url,
            username.text,
            String(password.password),
            prefix.text,
            suffix.text,
        )
    }

    override fun reset() {
        val settings = service<NexusIqSettings>()
        val state = settings.state
        serverUrl.text = state.serverUrl
        username.text = state.username
        password.text = settings.password.orEmpty()
        prefix.text = state.applicationIdPrefix
        suffix.text = state.applicationIdSuffix
    }

    override fun disposeUIResources() {
        panel = null
    }
}
