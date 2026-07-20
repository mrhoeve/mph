package nl.hicts.mph.intellij.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusIqSupportTest {
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
}
