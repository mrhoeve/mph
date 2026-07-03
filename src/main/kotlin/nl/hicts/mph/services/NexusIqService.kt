package nl.hicts.mph.services

import nl.hicts.mph.logging.LoggerDelegate
import nl.hicts.mph.models.Settings
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.io.File
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

@Service
class NexusIqService(
    private val settingsService: SettingsService,
    private val mavenCommandService: MavenCommandService
) {
    private val logger by LoggerDelegate()
    private val webClient = WebClient.builder().build()
    private val vulnerabilityCache = ConcurrentHashMap<String, List<NexusIqPolicyViolation>>()

    fun extractNexusIqAppId(projectPath: String, settings: Settings): String? {
        val projectDir = File(projectPath)
        val jenkinsfile = File(projectDir, "Jenkinsfile")
        
        if (!jenkinsfile.exists()) return null

        return try {
            val content = jenkinsfile.readText()
            val regex = Regex("(?:servicePipeline|libraryPipeline)\\s*\\(\\s*['\"]([^'\"]+)['\"]")
            val match = regex.find(content)
            val baseName = match?.groupValues?.get(1)
            
            if (baseName != null) {
                val prefix = settings.nexusIqAppIdPrefix ?: ""
                val suffix = settings.nexusIqAppIdSuffix ?: ""
                prefix + baseName + suffix
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to parse Jenkinsfile at ${jenkinsfile.absolutePath}", e)
            null
        }
    }

    fun scan(projectPath: String): CompletableFuture<String> {
        val settings = settingsService.loadSettings()
        val projectDir = File(projectPath)
        val pomFile = File(projectDir, "pom.xml")
        
        if (!pomFile.exists()) {
            return CompletableFuture.completedFuture("Error: pom.xml not found at $projectPath")
        }

        val serverUrl = settings.nexusIqUrl
        val user = settings.nexusIqUser
        val pass = settings.nexusIqPass
        
        val applicationId = extractNexusIqAppId(projectPath, settings)

        if (serverUrl.isNullOrBlank()) {
            return CompletableFuture.completedFuture("Error: Nexus IQ Server URL not configured")
        }
        
        if (applicationId == null) {
            return CompletableFuture.completedFuture("Nexus IQ scan skipped: No Jenkinsfile with servicePipeline or libraryPipeline found")
        }
        
        logger.info("Triggering Nexus IQ scan for $projectPath with App ID: $applicationId")

        val args = mutableListOf(
            "com.sonatype.clm:clm-maven-plugin:evaluate",
            "-Dclm.serverUrl=$serverUrl",
            "-Dclm.applicationId=$applicationId",
            "-Dclm.skipIfConfigurationMissing=false"
        )

        if (!user.isNullOrBlank()) {
            args.add("-Dclm.username=$user")
        }
        if (!pass.isNullOrBlank()) {
            args.add("-Dclm.password=$pass")
        }

        return mavenCommandService.runMavenCommandInBackground(projectDir, args, "Nexus IQ Scan")
            .thenApply { exitCode ->
                if (exitCode == 0) "Scan completed successfully for $projectPath"
                else "Scan failed for $projectPath with exit code $exitCode"
            }
    }

    fun getVulnerabilities(groupId: String, artifactId: String, version: String): List<NexusIqPolicyViolation> {
        return getVulnerabilitiesBatch(listOf(Triple(groupId, artifactId, version)))
    }

    fun getVulnerabilitiesBatch(components: List<Triple<String, String, String>>): List<NexusIqPolicyViolation> {
        val settings = settingsService.loadSettings()
        
        val results = mutableListOf<NexusIqPolicyViolation>()
        val toFetch = mutableListOf<Triple<String, String, String>>()

        for (comp in components) {
            val key = "${comp.first}:${comp.second}:${comp.third}"
            val cached = vulnerabilityCache[key]
            if (cached != null) {
                results.addAll(cached)
            } else {
                toFetch.add(comp)
            }
        }

        if (toFetch.isEmpty()) return results

        val serverUrl = settings.nexusIqUrl
        if (serverUrl.isNullOrBlank()) {
            return results
        }

        try {
            val request = ComponentDetailsRequest(
                components = toFetch.map { (g, a, v) ->
                    ComponentRequest(
                        ComponentIdentifier(
                            coordinates = mapOf(
                                "groupId" to g,
                                "artifactId" to a,
                                "version" to v,
                                "extension" to "jar"
                            )
                        )
                    )
                }
            )

            val response = webClient.post()
                .uri("$serverUrl/api/v2/components/details")
                .headers { headers ->
                    if (!settings.nexusIqUser.isNullOrBlank() && !settings.nexusIqPass.isNullOrBlank()) {
                        headers.setBasicAuth(settings.nexusIqUser, settings.nexusIqPass)
                    }
                }
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ComponentDetailsResponse::class.java)
                .block()

            response?.componentDetails?.forEach { detail ->
                val g = detail.componentIdentifier.coordinates["groupId"] ?: ""
                val a = detail.componentIdentifier.coordinates["artifactId"] ?: ""
                val v = detail.componentIdentifier.coordinates["version"] ?: ""
                val key = "$g:$a:$v"
                val remediationVersion = detail.remediation?.version
                
                val violations = detail.policyData?.policyViolations?.map { violation ->
                    NexusIqPolicyViolation(
                        componentIdentifier = "maven:$g:$a:$v",
                        threatLevel = violation.threatLevel,
                        policyName = violation.policyName,
                        constraintViolations = violation.constraintViolations.flatMap { cv -> cv.reasons.map { it.reason } },
                        remediationVersion = remediationVersion
                    )
                } ?: emptyList()
                
                vulnerabilityCache[key] = violations
                results.addAll(violations)
            }
        } catch (e: Exception) {
            logger.error("Failed to fetch vulnerabilities from Nexus IQ", e)
        }

        return results
    }

}

data class NexusIqResult(
    val applicationPublicId: String,
    val reportHtmlUrl: String? = null,
    val policyViolations: List<NexusIqPolicyViolation> = emptyList(),
    val message: String? = null
)

data class NexusIqPolicyViolation(
    val componentIdentifier: String,
    val threatLevel: Int,
    val policyName: String,
    val constraintViolations: List<String>,
    val remediationVersion: String?
)

// DTOs for Nexus IQ API
data class ComponentDetailsRequest(
    val components: List<ComponentRequest>
)

data class ComponentRequest(
    val componentIdentifier: ComponentIdentifier
)

data class ComponentIdentifier(
    val format: String = "maven",
    val coordinates: Map<String, String>
)

data class ComponentDetailsResponse(
    val componentDetails: List<ComponentDetail>
)

data class ComponentDetail(
    val componentIdentifier: ComponentIdentifier,
    val securityData: SecurityData?,
    val policyData: PolicyData?,
    val remediation: Remediation? = null
)

data class Remediation(
    val version: String? = null
)

data class SecurityData(
    val securityIssues: List<SecurityIssue>
)

data class SecurityIssue(
    val source: String,
    val reference: String,
    val severity: Double
)

data class PolicyData(
    val policyViolations: List<PolicyViolation>
)

data class PolicyViolation(
    val policyId: String,
    val policyName: String,
    val threatLevel: Int,
    val constraintViolations: List<ConstraintViolation>
)

data class ConstraintViolation(
    val constraintId: String,
    val constraintName: String,
    val reasons: List<Reason>
)

data class Reason(
    val reason: String,
    val reference: String? = null
)
