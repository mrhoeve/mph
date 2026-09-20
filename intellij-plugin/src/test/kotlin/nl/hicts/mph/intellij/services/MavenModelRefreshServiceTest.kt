package nl.hicts.mph.intellij.services

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MavenModelRefreshServiceTest {
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
