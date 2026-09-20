package nl.hicts.mph.intellij.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.idea.maven.buildtool.MavenSyncSpec
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.project.MavenProjectsManagerEx

@Service(Service.Level.PROJECT)
class MavenModelRefreshService(private val project: Project, private val scope: CoroutineScope) {
    fun reload(onSuccess: () -> Unit, onFailure: (Throwable) -> Unit) {
        val modality = ModalityState.current()
        launchMavenRefresh(scope, {
            val manager = MavenProjectsManager.getInstance(project) as MavenProjectsManagerEx
            manager.updateAllMavenProjects(MavenSyncSpec.full("MPH version alignment", true))
            check(manager.projects.none { it.hasReadingErrors() }) {
                "Resolve Maven model errors before aligning versions."
            }
        }, { callback -> ApplicationManager.getApplication().invokeLater(Runnable(callback), modality) }, onSuccess, onFailure)
    }
}

/** Completion also runs if project disposal cancels the scope before the coroutine starts. */
internal fun launchMavenRefresh(
    scope: CoroutineScope,
    refresh: suspend () -> Unit,
    dispatch: (() -> Unit) -> Unit,
    onSuccess: () -> Unit,
    onFailure: (Throwable) -> Unit,
): Job {
    var failure: Throwable? = null
    return scope.launch {
        try {
            refresh()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure = error
        }
    }.also { job ->
        job.invokeOnCompletion { cause ->
            dispatch {
                val error = failure ?: cause
                if (error == null) onSuccess() else onFailure(error)
            }
        }
    }
}
