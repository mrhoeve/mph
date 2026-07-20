package nl.hicts.mph.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import nl.hicts.mph.intellij.model.DependentProjectsAnalysis
import nl.hicts.mph.intellij.services.IdeaProjectDiscoveryService
import nl.hicts.mph.intellij.ui.DependentProjectsDialog

class UpdateDependentProjectsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible =
            event.project != null && isPomFile(event.getData(CommonDataKeys.VIRTUAL_FILE))
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val pomFile = event.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf(::isPomFile) ?: return

        FindDependentProjectsTask(
            project = project,
            findDependents = {
                project.service<IdeaProjectDiscoveryService>().findDependents(pomFile.path)
            },
            showAnalysis = { result -> DependentProjectsDialog(project, result).show() },
            showMissingProject = {
                    Messages.showInfoMessage(
                        project,
                        "Import this pom.xml as a Maven project before finding its dependents.",
                        "Maven Project Helper",
                    )
            },
            showError = { error ->
                Messages.showErrorDialog(
                    project,
                    error.message ?: "Unable to inspect the linked Maven projects.",
                    "Maven Project Helper",
                )
            },
        ).queue()
    }

    internal fun isPomFile(file: VirtualFile?): Boolean =
        file != null && isPomFileName(file.name, file.isDirectory)

    internal fun isPomFileName(name: String?, isDirectory: Boolean): Boolean =
        !isDirectory && name?.equals("pom.xml", ignoreCase = true) == true
}
