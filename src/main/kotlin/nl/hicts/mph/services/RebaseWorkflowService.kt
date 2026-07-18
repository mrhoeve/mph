package nl.hicts.mph.services

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class RebaseProgressStatus {
    PENDING, RUNNING, SUCCESS, CONFLICT, SKIPPED, FAILED, ALIGNING, COMPLETED, PARTIAL
}

data class RebaseProgress(
    val projectPath: String? = null,
    val artifactId: String? = null,
    val repositoryPath: String? = null,
    val status: RebaseProgressStatus,
    val message: String,
    val recoveryHint: String? = null,
    val stashPreserved: Boolean = false,
    val overall: Boolean = false,
    val alignmentSkipped: Boolean = false
)

data class RebaseStartResponse(
    val prefix: String,
    val repositories: List<RebaseRepositoryPlan>
)

@Service
class RebaseWorkflowService(
    private val mavenProjectService: MavenProjectService,
    private val gitService: GitService
) {
    private val logger = LoggerFactory.getLogger(RebaseWorkflowService::class.java)
    @Volatile
    private var eventSink = newEventSink()
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    fun events(): Flux<RebaseProgress> = Flux.defer { eventSink.asFlux() }

    fun start(basePath: Path, maxDepth: Int, rootProjectPaths: List<String>): RebaseStartResponse {
        val plan = mavenProjectService.prepareRebaseSelection(basePath, maxDepth, rootProjectPaths)
        check(running.compareAndSet(false, true)) { "A rebase operation is already running." }
        eventSink = newEventSink()

        plan.repositories.forEach { repository ->
            emit(repository, RebaseProgressStatus.PENDING, "Waiting to be rebased.")
        }
        executor.execute {
            try {
                executeRebase(basePath, maxDepth, plan)
            } catch (e: Exception) {
                logger.error("Rebase workflow failed", e)
                eventSink.tryEmitNext(
                    RebaseProgress(
                        status = RebaseProgressStatus.FAILED,
                        message = "Rebase workflow failed: ${e.message ?: e.javaClass.simpleName}",
                        recoveryHint = "Inspect the reported repositories before retrying.",
                        overall = true,
                        alignmentSkipped = true
                    )
                )
            } finally {
                running.set(false)
            }
        }
        return RebaseStartResponse(plan.prefix, plan.repositories)
    }

    internal fun executeRebase(basePath: Path, maxDepth: Int, plan: RebaseSelectionPlan) {
        val results = plan.repositories.map { repository ->
            emit(repository, RebaseProgressStatus.RUNNING, "Starting Git preflight.")
            val result = gitService.rebaseOnDevelop(repository.projectPath.toFile()) { step ->
                emit(repository, RebaseProgressStatus.RUNNING, step)
            }
            emit(
                repository,
                result.status.toProgressStatus(),
                result.message,
                result.recoveryHint,
                result.stashPreserved
            )
            result
        }

        if (results.any { it.status != DevelopRebaseStatus.SUCCESS }) {
            eventSink.tryEmitNext(
                RebaseProgress(
                    status = RebaseProgressStatus.PARTIAL,
                    message = "Rebasing finished with issues. Version alignment was skipped to avoid inconsistent changes.",
                    recoveryHint = "Resolve every reported repository, then run Version Update manually.",
                    overall = true,
                    alignmentSkipped = true
                )
            )
            return
        }

        eventSink.tryEmitNext(
            RebaseProgress(
                status = RebaseProgressStatus.ALIGNING,
                message = "Reapplying prefix '${plan.prefix}' to current versions and aligning all dependents.",
                overall = true
            )
        )
        mavenProjectService.realignPrefixedVersions(
            basePath,
            maxDepth,
            plan.rootProjectPaths,
            plan.prefix
        )
        eventSink.tryEmitNext(
            RebaseProgress(
                status = RebaseProgressStatus.COMPLETED,
                message = "All repositories were rebased and all module versions were realigned. Changes remain uncommitted.",
                overall = true
            )
        )
    }

    private fun emit(
        repository: RebaseRepositoryPlan,
        status: RebaseProgressStatus,
        message: String,
        recoveryHint: String? = null,
        stashPreserved: Boolean = false
    ) {
        eventSink.tryEmitNext(
            RebaseProgress(
                projectPath = repository.projectPath,
                artifactId = repository.artifactId,
                repositoryPath = repository.repositoryPath,
                status = status,
                message = message,
                recoveryHint = recoveryHint,
                stashPreserved = stashPreserved
            )
        )
    }

    private fun String.toFile() = java.io.File(this)

    private fun DevelopRebaseStatus.toProgressStatus() = when (this) {
        DevelopRebaseStatus.SUCCESS -> RebaseProgressStatus.SUCCESS
        DevelopRebaseStatus.CONFLICT -> RebaseProgressStatus.CONFLICT
        DevelopRebaseStatus.SKIPPED -> RebaseProgressStatus.SKIPPED
        DevelopRebaseStatus.FAILED -> RebaseProgressStatus.FAILED
    }

    private fun newEventSink(): Sinks.Many<RebaseProgress> = Sinks.many().replay().limit(MAX_REPLAYED_EVENTS)

    @PreDestroy
    fun close() {
        executor.shutdownNow()
    }

    private companion object {
        const val MAX_REPLAYED_EVENTS = 500
    }
}
