package nl.hicts.mph.intellij.services

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class NexusIqViolation(
    val component: String,
    val policy: String,
    val threatLevel: Int,
    val reasons: List<String>,
    val direct: Boolean,
    val waived: Boolean,
)

data class NexusComponent(
    val groupId: String,
    val artifactId: String,
    val version: String,
)

data class NexusComponentFinding(
    val component: NexusComponent,
    val threatLevel: Int,
    val policy: String,
    val reasons: List<String>,
    val remediationVersion: String?,
)

data class NexusIqReport(
    val reportUrl: String?,
    val violations: List<NexusIqViolation>,
) {
    val critical: Int get() = violations.count { it.threatLevel >= 8 }
    val severe: Int get() = violations.count { it.threatLevel in 4..7 }
    val moderate: Int get() = violations.count { it.threatLevel in 2..3 }
    val low: Int get() = violations.count { it.threatLevel < 2 }
}

object NexusIqSupport {
    private val pipeline = Regex("(?:servicePipeline|libraryPipeline)\\s*\\(\\s*['\"]([^'\"]+)['\"]")

    fun applicationId(jenkinsfile: String, prefix: String = "", suffix: String = ""): String? =
        pipeline.find(jenkinsfile)?.groupValues?.get(1)?.let { "$prefix$it$suffix" }

    fun internalApplicationId(json: String, publicId: String): String? =
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("applications")
            ?.mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }
            ?.firstOrNull { it.string("publicId") == publicId }
            ?.string("id")

    fun latestReport(json: String, serverUrl: String): Pair<String, String>? =
        JsonParser.parseString(json).asJsonArray
            .mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }
            .maxByOrNull { it.string("evaluationDate").orEmpty() }
            ?.let { report ->
                val html = report.string("reportHtmlUrl") ?: return@let null
                val resolved = if (html.startsWith("http://") || html.startsWith("https://")) html
                else "${serverUrl.trimEnd('/')}/${html.trimStart('/')}"
                resolved to (Regex("/report/([^/?#]+)").find(resolved)?.groupValues?.get(1).orEmpty())
            }
            ?.takeIf { it.second.isNotBlank() }

    fun policyReport(json: String, reportUrl: String?): NexusIqReport {
        val components = JsonParser.parseString(json).asJsonObject.getAsJsonArray("components") ?: return NexusIqReport(reportUrl, emptyList())
        val violations = components.flatMap { componentElement ->
            val component = componentElement.asJsonObject
            val identifier = component.string("displayName")
                ?: component.get("componentIdentifier")?.toString()
                ?: component.string("packageUrl")
                ?: "Unknown component"
            val direct = component.getAsJsonObject("dependencyData")?.get("directDependency")?.asBoolean == true
            component.getAsJsonArray("violations").orEmpty().mapNotNull { violationElement ->
                val violation = violationElement.asJsonObject
                val category = violation.string("policyThreatCategory")
                if (category != null && !category.equals("SECURITY", true)) return@mapNotNull null
                NexusIqViolation(
                    component = identifier,
                    policy = violation.string("policyName").orEmpty(),
                    threatLevel = violation.get("policyThreatLevel")?.asInt ?: 0,
                    reasons = violation.getAsJsonArray("constraints").orEmpty().flatMap { constraint ->
                        constraint.asJsonObject.getAsJsonArray("conditions").orEmpty().mapNotNull { condition ->
                            condition.asJsonObject.string("conditionReason")
                        }
                    }.distinct(),
                    direct = direct,
                    waived = violation.get("waived")?.asBoolean == true,
                )
            }
        }.sortedWith(compareByDescending<NexusIqViolation> { it.threatLevel }.thenBy(NexusIqViolation::component))
        return NexusIqReport(reportUrl, violations)
    }

    fun componentDetailsRequest(components: Collection<NexusComponent>): String {
        val root = JsonObject()
        val array = com.google.gson.JsonArray()
        components.distinct().forEach { component ->
            val coordinates = JsonObject().apply {
                addProperty("groupId", component.groupId)
                addProperty("artifactId", component.artifactId)
                addProperty("version", component.version)
                addProperty("extension", "jar")
            }
            val identifier = JsonObject().apply {
                addProperty("format", "maven")
                add("coordinates", coordinates)
            }
            array.add(JsonObject().apply { add("componentIdentifier", identifier) })
        }
        root.add("components", array)
        return root.toString()
    }

    fun componentFindings(json: String): List<NexusComponentFinding> {
        val details = JsonParser.parseString(json).asJsonObject.getAsJsonArray("componentDetails") ?: return emptyList()
        return details.flatMap { detailElement ->
            val detail = detailElement.asJsonObject
            val coordinates = detail.getAsJsonObject("componentIdentifier")?.getAsJsonObject("coordinates")
                ?: return@flatMap emptyList()
            val component = NexusComponent(
                coordinates.string("groupId").orEmpty(),
                coordinates.string("artifactId").orEmpty(),
                coordinates.string("version").orEmpty(),
            )
            if (component.groupId.isBlank() || component.artifactId.isBlank()) return@flatMap emptyList()
            val remediation = detail.getAsJsonObject("remediation")?.string("version")
            detail.getAsJsonObject("policyData")?.getAsJsonArray("policyViolations").orEmpty().map { violationElement ->
                val violation = violationElement.asJsonObject
                NexusComponentFinding(
                    component,
                    violation.get("threatLevel")?.asInt ?: 0,
                    violation.string("policyName").orEmpty(),
                    violation.getAsJsonArray("constraintViolations").orEmpty().flatMap { constraint ->
                        constraint.asJsonObject.getAsJsonArray("reasons").orEmpty().mapNotNull { reason ->
                            reason.asJsonObject.string("reason")
                        }
                    }.distinct(),
                    remediation,
                )
            }
        }.sortedByDescending(NexusComponentFinding::threatLevel)
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
}

private fun <T> Iterable<T>?.orEmpty(): Iterable<T> = this ?: emptyList()
