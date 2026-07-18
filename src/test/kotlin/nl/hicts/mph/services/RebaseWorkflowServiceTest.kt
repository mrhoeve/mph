package nl.hicts.mph.services

import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RebaseWorkflowServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val mavenProjectService = mockk<MavenProjectService>()
    private val gitService = mockk<GitService>()

    @Test
    fun `should preflight and execute an asynchronous start`() {
        val service = RebaseWorkflowService(mavenProjectService, gitService)
        val plan = plan().copy(repositories = plan().repositories.take(1))
        val completed = CountDownLatch(1)
        every {
            mavenProjectService.prepareRebaseSelection(tempDir, 3, plan.rootProjectPaths)
        } returns plan
        every { gitService.rebaseOnDevelop(any(), any()) } returns result(
            plan.repositories.single(), DevelopRebaseStatus.SUCCESS
        )
        justRun {
            mavenProjectService.realignPrefixedVersions(tempDir, 3, plan.rootProjectPaths, plan.prefix)
        }
        val response = service.start(tempDir, 3, plan.rootProjectPaths)
        val subscription = service.events().subscribe { event ->
            if (event.status == RebaseProgressStatus.COMPLETED) completed.countDown()
        }

        assertEquals(plan.prefix, response.prefix)
        assertEquals(plan.repositories, response.repositories)
        assertTrue(completed.await(10, TimeUnit.SECONDS))
        subscription.dispose()
        service.close()
    }

    @Test
    fun `should publish an overall failure when asynchronous alignment fails`() {
        val service = RebaseWorkflowService(mavenProjectService, gitService)
        val plan = plan().copy(repositories = plan().repositories.take(1))
        val failed = CountDownLatch(1)
        val events = mutableListOf<RebaseProgress>()
        every {
            mavenProjectService.prepareRebaseSelection(tempDir, 3, plan.rootProjectPaths)
        } returns plan
        every { gitService.rebaseOnDevelop(any(), any()) } returns result(
            plan.repositories.single(), DevelopRebaseStatus.SUCCESS
        )
        every {
            mavenProjectService.realignPrefixedVersions(tempDir, 3, plan.rootProjectPaths, plan.prefix)
        } throws IllegalStateException("alignment unavailable")
        service.start(tempDir, 3, plan.rootProjectPaths)
        val subscription = service.events().subscribe { event ->
            events.add(event)
            if (event.overall && event.status == RebaseProgressStatus.FAILED) failed.countDown()
        }

        assertTrue(failed.await(10, TimeUnit.SECONDS))
        assertTrue(events.last().alignmentSkipped)
        assertTrue(events.last().message.contains("alignment unavailable"))
        subscription.dispose()
        service.close()
    }

    @Test
    fun `should realign versions only after every repository succeeds`() {
        val service = RebaseWorkflowService(mavenProjectService, gitService)
        val plan = plan()
        val events = mutableListOf<RebaseProgress>()
        val subscription = service.events().subscribe(events::add)
        every { gitService.rebaseOnDevelop(any(), any()) } returnsMany listOf(
            result(plan.repositories[0], DevelopRebaseStatus.SUCCESS),
            result(plan.repositories[1], DevelopRebaseStatus.SUCCESS)
        )
        justRun {
            mavenProjectService.realignPrefixedVersions(
                tempDir, 3, plan.rootProjectPaths, plan.prefix
            )
        }

        service.executeRebase(tempDir, 3, plan)

        verify(exactly = 1) {
            mavenProjectService.realignPrefixedVersions(tempDir, 3, plan.rootProjectPaths, plan.prefix)
        }
        assertEquals(RebaseProgressStatus.COMPLETED, events.last().status)
        assertTrue(events.any { it.status == RebaseProgressStatus.ALIGNING })
        subscription.dispose()
        service.close()
    }

    @Test
    fun `should continue repositories but skip alignment after a conflict`() {
        val service = RebaseWorkflowService(mavenProjectService, gitService)
        val plan = plan()
        val events = mutableListOf<RebaseProgress>()
        val subscription = service.events().subscribe(events::add)
        every { gitService.rebaseOnDevelop(any(), any()) } returnsMany listOf(
            result(plan.repositories[0], DevelopRebaseStatus.CONFLICT, stashPreserved = true),
            result(plan.repositories[1], DevelopRebaseStatus.SUCCESS)
        )

        service.executeRebase(tempDir, 3, plan)

        verify(exactly = 2) { gitService.rebaseOnDevelop(any(), any()) }
        verify(exactly = 0) { mavenProjectService.realignPrefixedVersions(any(), any(), any(), any()) }
        assertEquals(RebaseProgressStatus.PARTIAL, events.last().status)
        assertTrue(events.last().alignmentSkipped)
        assertTrue(events.any { it.status == RebaseProgressStatus.CONFLICT && it.stashPreserved })
        subscription.dispose()
        service.close()
    }

    private fun plan() = RebaseSelectionPlan(
        prefix = "PREFIX-1234-",
        rootProjectPaths = listOf("first/pom.xml", "second/pom.xml"),
        repositories = listOf(
            RebaseRepositoryPlan("first/pom.xml", "first", tempDir.resolve("first").toString()),
            RebaseRepositoryPlan("second/pom.xml", "second", tempDir.resolve("second").toString())
        )
    )

    private fun result(
        repository: RebaseRepositoryPlan,
        status: DevelopRebaseStatus,
        stashPreserved: Boolean = false
    ) = DevelopRebaseResult(
        repository.repositoryPath,
        "feature/upgrade",
        status,
        status.name,
        if (status == DevelopRebaseStatus.CONFLICT) "Resolve manually" else null,
        stashPreserved
    )
}
