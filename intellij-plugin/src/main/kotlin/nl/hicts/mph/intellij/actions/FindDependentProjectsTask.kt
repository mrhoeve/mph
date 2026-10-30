package nl.hicts.mph.intellij.actions

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import nl.hicts.mph.intellij.model.DependentProjectsAnalysis

internal class FindDependentProjectsTask(
    project: Project,
    private val findDependents: () -> DependentProjectsAnalysis?,
    private val showAnalysis: (DependentProjectsAnalysis) -> Unit,
    private val showMissingProject: () -> Unit,
    private val showError: (Throwable) -> Unit,
) : Task.Backgroundable(project, "Finding dependent Maven projects", false) {
    private var analysis: DependentProjectsAnalysis? = null

    override fun run(indicator: ProgressIndicator) {
        analysis = findDependents()
    }

    override fun onSuccess() {
        analysis?.let(showAnalysis) ?: showMissingProject()
    }

    override fun onThrowable(error: Throwable) = showError(error)
}
