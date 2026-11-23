package nl.hicts.mph.intellij.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import org.jetbrains.idea.maven.buildtool.MavenSyncSpec
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.project.MavenProjectsManagerEx
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class MavenModelRefreshService(private val project: Project, private val scope: CoroutineScope) {
    fun reload(onSuccess: () -> Unit, onFailure: (Throwable) -> Unit) {
        reloadModel(onSuccess, onFailure) { manager ->
            manager.updateAllMavenProjects(MavenSyncSpec.full("MPH version alignment", true))
            check(manager.projects.none { it.hasReadingErrors() }) {
                "Resolve Maven model errors before aligning versions."
            }
        }
    }

    fun reloadChangedPoms(paths: Set<String>, onSuccess: () -> Unit, onFailure: (Throwable) -> Unit) {
        reloadModel(onSuccess, onFailure) { manager ->
            val fileSystem = LocalFileSystem.getInstance()
            synchronizeChangedMavenProjects(
                paths,
                findFile = { fileSystem.findFileByNioFile(Path.of(it)) },
                findProject = { manager.findProject(it) },
                inheritors = manager::findInheritors,
                projectFile = { it.file },
                hasReadingErrors = { it.hasReadingErrors() },
                sync = { manager.updateMavenProjects(MavenSyncSpec.incremental("MPH version update", true), it, emptyList()) },
            )
        }
    }

    private fun reloadModel(
        onSuccess: () -> Unit,
        onFailure: (Throwable) -> Unit,
        sync: suspend (MavenProjectsManagerEx) -> Unit,
    ) {
        val modality = ModalityState.current()
        launchMavenRefresh(scope, {
            val manager = MavenProjectsManager.getInstance(project) as MavenProjectsManagerEx
            sync(manager)
        }, { callback -> ApplicationManager.getApplication().invokeLater(Runnable(callback), modality) }, onSuccess, onFailure)
    }
}

internal suspend fun <File : Any, Model : Any> synchronizeChangedMavenProjects(
    paths: Set<String>,
    findFile: (String) -> File?,
    findProject: (File) -> Model?,
    inheritors: (Model) -> Collection<Model>,
    projectFile: (Model) -> File,
    hasReadingErrors: (Model) -> Boolean,
    sync: suspend (List<File>) -> Unit,
) {
    if (paths.isEmpty()) return
    val changed = paths.map { path ->
        val file = findFile(path) ?: error("POM is unavailable: $path")
        findProject(file) ?: error("POM is no longer linked as a Maven project: $path")
    }
    val files = affectedMavenProjects(changed, inheritors).map(projectFile).distinct()
    sync(files)
    check(files.all { findProject(it)?.let(hasReadingErrors) == false }) {
        "One or more affected Maven projects could not be read."
    }
}

private fun <T> affectedMavenProjects(changed: Collection<T>, inheritors: (T) -> Collection<T>): Set<T> {
    val affected = linkedSetOf<T>()
    val pending = ArrayDeque(changed)
    while (pending.isNotEmpty()) {
        val next = pending.removeFirst()
        if (affected.add(next)) pending.addAll(inheritors(next))
    }
    return affected
}

/** Completion also runs if project disposal cancels the scope before the coroutine starts. */
internal fun launchMavenRefresh(
    scope: CoroutineScope,
    refresh: suspend () -> Unit,
    dispatch: (() -> Unit) -> Unit,
    onSuccess: () -> Unit,
    onFailure: (Throwable) -> Unit,
): Job {
    val failure = AtomicReference<Throwable?>()
    return scope.launch {
        try {
            refresh()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure.set(error)
        }
    }.also { job ->
        job.invokeOnCompletion { cause ->
            dispatch {
                val error = failure.get() ?: cause
                if (error == null) onSuccess() else onFailure(error)
            }
        }
    }
}
