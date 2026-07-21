package nl.hicts.mph.intellij.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NexusIqSupportTest {
    @Test
    fun `finds the closest Jenkinsfile up to the repository root`() {
        val repository = Files.createTempDirectory("mph-nexus-jenkinsfile-")
        try {
            val module = Files.createDirectories(repository.resolve("modules/sample"))
            val pom = Files.writeString(module.resolve("pom.xml"), "<project/>")
            val rootJenkinsfile = Files.writeString(repository.resolve("Jenkinsfile"), "servicePipeline('sample')")

            assertEquals(rootJenkinsfile, NexusIqSupport.findJenkinsfile(pom.toString(), repository.toString()))

            val moduleJenkinsfile = Files.writeString(module.resolve("Jenkinsfile"), "libraryPipeline('sample-module')")
            assertEquals(moduleJenkinsfile, NexusIqSupport.findJenkinsfile(pom.toString(), repository.toString()))
            Files.delete(moduleJenkinsfile)
            assertNull(NexusIqSupport.findJenkinsfile(pom.toString(), module.toString()))
        } finally {
            repository.toFile().deleteRecursively()
        }
    }

    @Test
    fun `extracts application ID from supported Jenkins pipelines`() {
        assertEquals(
            "team-sample-service-main",
            NexusIqSupport.applicationId("servicePipeline('sample-service')", "team-", "-main"),
        )
        assertEquals("sample-library", NexusIqSupport.applicationId("libraryPipeline( \"sample-library\" )"))
        assertNull(NexusIqSupport.applicationId("pipeline { agent any }"))
    }

    @Test
    fun `finds internal application and newest report`() {
        val applications = """{"applications":[{"id":"internal-1","publicId":"sample-service"}]}"""
        val reports = """[
            {"evaluationDate":"2026-01-01T10:00:00Z","reportHtmlUrl":"/ui/links/application/sample/report/old"},
            {"evaluationDate":"2026-02-01T10:00:00Z","reportHtmlUrl":"/ui/links/application/sample/report/new"}
        ]"""

        assertEquals("internal-1", NexusIqSupport.internalApplicationId(applications, "sample-service"))
        assertEquals(
            "https://iq.example.org/ui/links/application/sample/report/new" to "new",
            NexusIqSupport.latestReport(reports, "https://iq.example.org/"),
        )
    }

    @Test
    fun `parses security policy violations and ignores other categories`() {
        val policy = """{
          "components": [{
            "displayName": "org.example:sample-library:1.0.0",
            "dependencyData": {"directDependency": true},
            "violations": [
              {"policyName":"Critical Security","policyThreatLevel":9,"policyThreatCategory":"SECURITY","waived":false,
               "constraints":[{"conditions":[{"conditionReason":"Known vulnerability"}]}]},
              {"policyName":"License","policyThreatLevel":5,"policyThreatCategory":"LICENSE","waived":false,"constraints":[]}
            ]
          }]
        }"""

        val result = NexusIqSupport.policyReport(policy, "https://iq.example.org/report/1")

        assertEquals(1, result.violations.size)
        assertEquals(1, result.critical)
        assertEquals("Critical Security", result.violations.single().policy)
        assertTrue(result.violations.single().direct)
        assertFalse(result.violations.single().waived)
        assertEquals(listOf("Known vulnerability"), result.violations.single().reasons)
    }

    @Test
    fun `summarizes every Nexus IQ threat band`() {
        val report = NexusIqReport(
            null,
            listOf(9, 7, 4, 3, 2, 1, 0).map { level ->
                NexusIqViolation("component-$level", "policy", level, emptyList(), false, false)
            },
        )

        assertEquals(1, report.critical)
        assertEquals(2, report.severe)
        assertEquals(2, report.moderate)
        assertEquals(2, report.low)
    }

    @Test
    fun `handles incomplete application and report API responses`() {
        assertNull(NexusIqSupport.internalApplicationId("""{"applications":["invalid",{"publicId":"other"}]}""", "sample"))
        assertNull(NexusIqSupport.internalApplicationId("""{"applications":[{"publicId":"sample","id":null}]}""", "sample"))
        assertNull(NexusIqSupport.latestReport("[]", "https://iq.example.org"))
        assertNull(
            NexusIqSupport.latestReport(
                """["invalid",{"evaluationDate":"2026-01-01T00:00:00Z"}]""",
                "https://iq.example.org",
            ),
        )
        assertNull(
            NexusIqSupport.latestReport(
                """[{"evaluationDate":"2026-01-01T00:00:00Z","reportHtmlUrl":"/without-report-id"}]""",
                "https://iq.example.org",
            ),
        )
        assertEquals(
            "https://reports.example.org/report/absolute" to "absolute",
            NexusIqSupport.latestReport(
                """[{"evaluationDate":"2026-01-01T00:00:00Z","reportHtmlUrl":"https://reports.example.org/report/absolute"}]""",
                "https://iq.example.org",
            ),
        )
        assertEquals(
            "http://reports.example.org/report/plain" to "plain",
            NexusIqSupport.latestReport(
                """[{"evaluationDate":null,"reportHtmlUrl":"http://reports.example.org/report/plain"}]""",
                "https://iq.example.org",
            ),
        )
    }

    @Test
    fun `parses optional policy fields with safe defaults`() {
        assertTrue(NexusIqSupport.policyReport("{}", null).violations.isEmpty())
        val policy = """{
          "components": [
            {"componentIdentifier":{"format":"maven"},"violations":[{"policyThreatLevel":2,"waived":true}]},
            {"packageUrl":"pkg:maven/org.example/sample@1","violations":[{"policyName":null,"policyThreatCategory":null}]},
            {"violations":[{"constraints":[{"conditions":[{}, {"conditionReason":null}]}]}]},
            {"displayName":"no-violations"}
          ]
        }"""

        val result = NexusIqSupport.policyReport(policy, null)

        assertEquals(3, result.violations.size)
        assertEquals(1, result.moderate)
        assertEquals(2, result.low)
        assertTrue(result.violations.any { it.component.contains("maven") && it.waived })
        assertTrue(result.violations.any { it.component.startsWith("pkg:maven") && !it.direct })
        assertTrue(result.violations.any { it.component == "Unknown component" && it.reasons.isEmpty() })
    }

    @Test
    fun `creates component requests and parses remediation recommendations`() {
        val component = NexusComponent("org.example", "sample-library", "1.0.0")
        val request = NexusIqSupport.componentDetailsRequest(listOf(component, component))
        assertEquals(1, com.google.gson.JsonParser.parseString(request).asJsonObject.getAsJsonArray("components").size())

        val response = """{
          "componentDetails": [{
            "componentIdentifier": {"coordinates": {
              "groupId":"org.example", "artifactId":"sample-library", "version":"1.0.0"
            }},
            "remediation": {"version":"1.2.3"},
            "policyData": {"policyViolations": [{
              "policyName":"Critical Security", "threatLevel":9,
              "constraintViolations":[{"reasons":[{"reason":"Known vulnerability"}]}]
            }]}
          }]
        }"""
        val finding = NexusIqSupport.componentFindings(response).single()
        assertEquals(component, finding.component)
        assertEquals("1.2.3", finding.remediationVersion)
        assertEquals(listOf("Known vulnerability"), finding.reasons)
        assertEquals(9, finding.threatLevel)
    }
}
