package nl.hicts.mph.intellij.services

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorkspaceOperationCoordinatorTest {
    @Test
    fun `lease spans threads rejects competing operations and permits owned alignment`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            WorkspaceOperationCoordinator.acquire("Synchronization").use { owner ->
                val rejected = executor.submit<Boolean> {
                    assertThrows(IllegalStateException::class.java) {
                        WorkspaceOperationCoordinator.run("Maven build") { fail("Build must not start") }
                    }
                    WorkspaceOperationCoordinator.run("Version update", owner) { true }
                }.get(5, TimeUnit.SECONDS)
                assertTrue(rejected)
            }
            WorkspaceOperationCoordinator.run("Maven build") { }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `failure releases lease and a stale owner cannot release or reuse the next lease`() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceOperationCoordinator.run("Failing operation") { throw IllegalArgumentException("Test failure") }
        }
        val previous = WorkspaceOperationCoordinator.acquire("Previous operation")
        previous.close()
        WorkspaceOperationCoordinator.acquire("Current operation").use {
            previous.close()
            assertThrows(IllegalStateException::class.java) {
                WorkspaceOperationCoordinator.run("Stale alignment", previous) { fail("Stale owner") }
            }
            assertThrows(IllegalStateException::class.java) { WorkspaceOperationCoordinator.acquire("Conflicting build") }
        }
    }

    @Test
    fun `mutation services reject overlap before touching files or git`() {
        WorkspaceOperationCoordinator.acquire("Synchronization").use {
            assertThrows(IllegalStateException::class.java) {
                GitWorkspaceService().createOrCheckoutBranch(listOf("missing-repository"), "feature/test")
            }
        }
    }
}
