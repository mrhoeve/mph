package nl.hicts.mph.intellij.services

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.test.assertFailsWith

class MavenModelRefreshServiceTest {
    @Test
    fun `targeted sync includes transitive inheritors and changed references only once`() = runBlocking {
        val workspace = SyncWorkspace()
        workspace.run(setOf("parent", "child", "changed-dependent"))
        assertEquals(listOf("parent", "child", "changed-dependent", "grandchild"), workspace.synced.single())
    }

    @Test
    fun `empty changes do not start a sync`() = runBlocking {
        val workspace = SyncWorkspace()
        workspace.run(emptySet())
        assertTrue(workspace.synced.isEmpty())
    }

    @Test
    fun `unavailable and unlinked POMs fail before any sync`() = runBlocking {
        val workspace = SyncWorkspace()
        assertEquals("POM is unavailable: missing", assertFailsWith<IllegalStateException> {
            workspace.run(setOf("parent", "missing"))
        }.message)
        assertEquals("POM is no longer linked as a Maven project: unlinked", assertFailsWith<IllegalStateException> {
            workspace.run(setOf("parent", "unlinked"))
        }.message)
        assertTrue(workspace.synced.isEmpty())
    }

    @Test
    fun `reading errors and projects removed during sync are reported`() = runBlocking {
        for (unlink in listOf(false, true)) {
            val workspace = SyncWorkspace()
            val failure = assertFailsWith<IllegalStateException> {
                workspace.run(setOf("parent")) {
                    if (unlink) workspace.linked.remove("grandchild")
                    else workspace.readingErrors += "grandchild"
                }
            }
            assertEquals("One or more affected Maven projects could not be read.", failure.message)
            assertEquals(listOf("parent", "child", "grandchild"), workspace.synced.single())
        }
    }

    private class SyncWorkspace {
        val linked = mutableSetOf("parent", "child", "grandchild", "changed-dependent", "other-parent", "unrelated")
        val readingErrors = mutableSetOf<String>()
        val synced = mutableListOf<List<String>>()
        private val children = mapOf(
            "parent" to listOf("child"),
            "child" to listOf("grandchild"),
            "other-parent" to listOf("unrelated"),
        )
        private val files = linked + "unlinked"

        suspend fun run(paths: Set<String>, afterSync: () -> Unit = {}) = synchronizeChangedMavenProjects(
            paths,
            findFile = { it.takeIf(files::contains) },
            findProject = { it.takeIf(linked::contains) },
            inheritors = { children[it].orEmpty() },
            projectFile = { it },
            hasReadingErrors = readingErrors::contains,
            sync = { synced += it; afterSync() },
        )
    }

    @Test
    fun `background refresh failure reaches the dispatched callback`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val callbacks = java.util.concurrent.LinkedBlockingQueue<() -> Unit>()
        val expected = IllegalStateException("Test background refresh failure")
        var failure: Throwable? = null
        try {
            val job = launchMavenRefresh(scope, { throw expected }, callbacks::add,
                { fail("Unexpected success") }, { failure = it })
            withTimeout(5_000) { job.join() }
            assertNull(failure)
            callbacks.remove().invoke()
            assertSame(expected, failure)
            assertTrue(scope.isActive)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `completion is dispatched only after refresh finishes`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val callbacks = mutableListOf<() -> Unit>()
        var successes = 0
        try {
            launchMavenRefresh(scope, { gate.await() }, callbacks::add, { successes++ }, { throw AssertionError(it) })
            assertTrue(callbacks.isEmpty())
            gate.complete(Unit)
            assertEquals(0, successes)
            callbacks.single().invoke()
            assertEquals(1, successes)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `refresh exception reports failure without cancelling the service scope`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var failure: Throwable? = null
        try {
            launchMavenRefresh(scope, { error("Test refresh failure") }, { it() }, { fail("Unexpected success") }, { failure = it })
            assertEquals("Test refresh failure", failure?.message)
            assertTrue(scope.isActive)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `project disposal before start and during refresh both complete with cancellation`() {
        for (cancelBeforeStart in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val gate = CompletableDeferred<Unit>()
            val failures = mutableListOf<Throwable>()
            if (cancelBeforeStart) scope.cancel()
            launchMavenRefresh(scope, { gate.await() }, { it() }, { fail("Unexpected success") }, failures::add)
            scope.cancel()
            assertEquals(1, failures.size)
            assertTrue(failures.single() is CancellationException)
        }
    }
}
