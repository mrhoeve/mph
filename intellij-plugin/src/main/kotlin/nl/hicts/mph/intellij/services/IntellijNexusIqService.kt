package nl.hicts.mph.intellij.services

import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.util.io.HttpRequests
import nl.hicts.mph.intellij.model.MavenProjectInfo
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

data class NexusIqScanResult(
    val applicationId: String,
    val exitCode: Int,
    val report: NexusIqReport?,
)

@Service(Service.Level.PROJECT)
class IntellijNexusIqService(private val project: Project) {
    fun componentFindings(components: Collection<NexusComponent>): List<NexusComponentFinding> {
        if (components.isEmpty()) return emptyList()
        val configuration = service<NexusIqSettings>()
        require(configuration.configured()) { "Configure the Nexus IQ server first." }
        val state = configuration.state
        val json = post(
            "${state.serverUrl.trimEnd('/')}/api/v2/components/details",
            state.username,
            configuration.password,
            NexusIqSupport.componentDetailsRequest(components),
        )
        return NexusIqSupport.componentFindings(json)
    }

    fun applicationId(projectInfo: MavenProjectInfo): String? {
        val settings = service<NexusIqSettings>().state
        val jenkinsfile = Path.of(projectInfo.pomPath).parent.resolve("Jenkinsfile")
        if (!Files.isRegularFile(jenkinsfile)) return null
        return NexusIqSupport.applicationId(
            Files.readString(jenkinsfile),
            settings.applicationIdPrefix,
            settings.applicationIdSuffix,
        )
    }

    fun scan(
        projectInfo: MavenProjectInfo,
        indicator: ProgressIndicator,
        output: (String) -> Unit,
    ): NexusIqScanResult {
        val configuration = service<NexusIqSettings>()
        require(configuration.configured()) { "Configure the Nexus IQ server first." }
        val applicationId = applicationId(projectInfo)
            ?: throw IllegalArgumentException("No servicePipeline or libraryPipeline application ID was found in Jenkinsfile.")
        val password = configuration.password
        val state = configuration.state
        val goals = buildList {
            add("com.sonatype.clm:clm-maven-plugin:evaluate")
            add("-Dclm.serverUrl=${state.serverUrl}")
            add("-Dclm.applicationId=$applicationId")
            add("-Dclm.skipIfConfigurationMissing=false")
            if (state.username.isNotBlank()) add("-Dclm.username=${state.username}")
            if (!password.isNullOrBlank()) add("-Dclm.password=$password")
        }
        val commandLine = project.service<MavenBuildService>().commandLine(
            projectInfo,
            MavenBuildOptions(goals, skipUnitTests = false, skipIntegrationTests = false),
        )
        val handler = OSProcessHandler(commandLine)
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                output(if (password.isNullOrBlank()) event.text else event.text.replace(password, "••••"))
            }
        })
        handler.startNotify()
        while (!handler.waitFor(250)) {
            if (indicator.isCanceled) handler.destroyProcess()
        }
        val exitCode = handler.exitCode ?: -1
        val report = runCatching { fetchReport(applicationId, state.serverUrl, state.username, password) }
            .onFailure { output("\nCould not retrieve Nexus IQ report details: ${it.message}\n") }
            .getOrNull()
        return NexusIqScanResult(applicationId, exitCode, report)
    }

    private fun fetchReport(applicationId: String, serverUrl: String, username: String, password: String?): NexusIqReport? {
        val applications = get("$serverUrl/api/v2/applications?publicId=${url(applicationId)}", username, password)
        val internalId = NexusIqSupport.internalApplicationId(applications, applicationId) ?: return null
        val reports = get("$serverUrl/api/v2/reports/applications/${url(internalId)}", username, password)
        val (reportUrl, reportId) = NexusIqSupport.latestReport(reports, serverUrl) ?: return null
        val policy = get(
            "$serverUrl/api/v2/applications/${url(applicationId)}/reports/${url(reportId)}/policy",
            username,
            password,
        )
        return NexusIqSupport.policyReport(policy, reportUrl)
    }

    private fun get(url: String, username: String, password: String?): String =
        HttpRequests.request(url).tuner { connection ->
            if (username.isNotBlank() && !password.isNullOrBlank()) {
                val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
                connection.setRequestProperty("Authorization", "Basic $token")
            }
            connection.setRequestProperty("Accept", "application/json")
        }.readString()

    private fun post(url: String, username: String, password: String?, body: String): String =
        HttpRequests.post(url, "application/json").tuner { connection ->
            if (username.isNotBlank() && !password.isNullOrBlank()) {
                val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
                connection.setRequestProperty("Authorization", "Basic $token")
            }
            connection.setRequestProperty("Accept", "application/json")
        }.connect { request ->
            request.write(body)
            request.readString()
        }

    private fun url(value: String): String = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
