package nl.hicts.mph.intellij.services

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "MphNexusIqSettings", storages = [Storage("mph.xml")])
@Service(Service.Level.APP)
class NexusIqSettings : PersistentStateComponent<NexusIqSettings.State> {
    class State {
        var serverUrl: String = ""
        var username: String = ""
        var applicationIdPrefix: String = ""
        var applicationIdSuffix: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var password: String?
        get() = PasswordSafe.instance.getPassword(credentialsKey())
        set(value) {
            PasswordSafe.instance.set(
                credentialsKey(),
                value?.takeIf(String::isNotBlank)?.let { Credentials(state.username, it) },
            )
        }

    fun update(serverUrl: String, username: String, password: String?, prefix: String, suffix: String) {
        state.serverUrl = serverUrl.trim().trimEnd('/')
        state.username = username.trim()
        state.applicationIdPrefix = prefix.trim()
        state.applicationIdSuffix = suffix.trim()
        if (password != null) this.password = password
    }

    fun configured(): Boolean = state.serverUrl.isNotBlank()

    private fun credentialsKey() = CredentialAttributes("nl.hicts.mph.nexus-iq")
}
