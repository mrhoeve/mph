package nl.hicts.mph.intellij.services

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import nl.hicts.mph.intellij.model.*
import java.nio.file.Files

class PluginBehaviorContractTest : BasePlatformTestCase() {
    fun testFeaturePrefixAndDependentAlignmentContract() {
        val root = Files.createTempDirectory("mph-contract-")
        try {
            val libraryPath = Files.createDirectories(root.resolve("library")).resolve("pom.xml")
            val consumerPath = Files.createDirectories(root.resolve("consumer")).resolve("pom.xml")
            Files.writeString(libraryPath, fixture("library.before.xml"))
            Files.writeString(consumerPath, fixture("consumer.before.xml"))
            val library = MavenProjectInfo("org.example", "library", "FEATURE-1.0-SNAPSHOT", libraryPath.toString(), libraryPath.parent.toString())
            val consumer = MavenProjectInfo("org.example", "consumer", "1.0-SNAPSHOT", consumerPath.toString(), consumerPath.parent.toString())
            val service = project.service<BulkVersionUpdateService>()
            val request = BulkVersionUpdateRequest(listOf(library), listOf(library, consumer), "FEATURE-", BulkVersionMode.ADD_PREFIX, true, true)
            val plan = service.prepare(request)
            assertEquals(listOf(consumer.pomPath), plan.edits.map { it.project.pomPath })
            service.apply(plan)
            assertEquals(fixture("library.before.xml"), Files.readString(libraryPath))
            assertEquals(fixture("consumer.after.xml"), Files.readString(consumerPath))
            assertTrue(service.prepare(request).edits.isEmpty())
            val descriptors = listOf(
                MavenProjectDependencyDescriptor(library, null, emptySet(), emptySet()),
                MavenProjectDependencyDescriptor(consumer, null, PomDependencyDeclarations.parse(fixture("consumer.before.xml")).dependencies, emptySet()),
            )
            assertEquals(listOf(library, consumer), WorkspaceDependencyAnalyzer().buildOrderForSelection(listOf(consumer, library), descriptors).entries.map { it.project })
        } finally { root.toFile().deleteRecursively() }
    }

    fun testCommittedConflictResolutionContract() {
        assertEquals(fixture("committed-version-resolved.xml"), GitVersionConflictResolver.resolvedContent(fixture("committed-version-conflict.xml")))
        assertNull(GitVersionConflictResolver.resolvedContent(fixture("mixed-conflict.xml")))
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.getResourceAsStream("/behavior-contract/$name"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }.replace("\r\n", "\n")
}
