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

    private fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
}

private fun <T> Iterable<T>?.orEmpty(): Iterable<T> = this ?: emptyList()
