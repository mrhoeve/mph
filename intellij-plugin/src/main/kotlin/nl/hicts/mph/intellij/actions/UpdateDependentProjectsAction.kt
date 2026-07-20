package nl.hicts.mph.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import nl.hicts.mph.intellij.model.DependentProjectsAnalysis
import nl.hicts.mph.intellij.services.IdeaProjectDiscoveryService
import nl.hicts.mph.intellij.services.DependentProjectVersionUpdateService
import nl.hicts.mph.intellij.services.DependentProjectsUpdateResult
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
        val targetPomContent = FileDocumentManager.getInstance().getDocument(pomFile)?.text

        FindDependentProjectsTask(
            project = project,
            findDependents = {
                project.service<IdeaProjectDiscoveryService>().findDependents(pomFile.path, targetPomContent)
            },
            showAnalysis = { result -> showUpdateDialog(project, result) },
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

    private fun showUpdateDialog(project: Project, analysis: DependentProjectsAnalysis) {
        val dialog = DependentProjectsDialog(project, analysis)
        if (!dialog.showAndGet() || !dialog.canApplyUpdate) return

        val result = project.service<DependentProjectVersionUpdateService>().update(analysis)
        showUpdateResult(project, result)
    }

    private fun showUpdateResult(project: Project, result: DependentProjectsUpdateResult) {
        val summary = if (result.updatedProjectCount > 0) {
            "Updated ${result.updatedReferenceCount} Maven reference(s) in " +
                "${result.updatedProjectCount} project(s)."
        } else {
            "No Maven references were updated."
        }
        val issueText = result.issues.take(4).joinToString("<br>") { StringUtil.escapeXmlEntities(it) }
        val remainingIssues = result.issues.size - minOf(result.issues.size, 4)
        val details = buildString {
            append(summary)
            if (result.unchangedProjectCount > 0) {
                append("<br>${result.unchangedProjectCount} project(s) were unchanged.")
            }
            if (issueText.isNotEmpty()) append("<br><br>$issueText")
            if (remainingIssues > 0) append("<br>…and $remainingIssues more issue(s).")
            if (result.updatedProjectCount > 0) append("<br><br>Use Undo to revert all changes.")
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Maven Project Helper")
            .createNotification(
                "Dependent Maven Projects",
                details,
                if (result.issues.isEmpty()) NotificationType.INFORMATION else NotificationType.WARNING,
            )
            .notify(project)
    }

    internal fun isPomFile(file: VirtualFile?): Boolean =
        file != null && isPomFileName(file.name, file.isDirectory)

    internal fun isPomFileName(name: String?, isDirectory: Boolean): Boolean =
        !isDirectory && name?.equals("pom.xml", ignoreCase = true) == true
}
