package nl.hicts.mph.intellij.services

class WorkspaceOperationBusyException(message: String) : IllegalStateException(message)

/** Serializes MPH mutations across IDE projects; leases may span background and UI callbacks. */
object WorkspaceOperationCoordinator {
    private var active: Lease? = null

    class Lease internal constructor(val name: String) : AutoCloseable {
        override fun close() = release(this)
    }

    @Synchronized
    fun acquire(name: String): Lease {
        if (active != null) throw WorkspaceOperationBusyException("${active?.name} is already running. Wait for it to finish before starting $name.")
        return Lease(name).also { active = it }
    }

    @Synchronized
    private fun release(lease: Lease) {
        if (active === lease) active = null
    }

    fun <T> run(name: String, owner: Lease? = null, action: () -> T): T {
        if (owner != null) {
            synchronized(this) { check(active === owner) { "The workspace operation has already finished." } }
            return action()
        }
        return acquire(name).use { action() }
    }
}
