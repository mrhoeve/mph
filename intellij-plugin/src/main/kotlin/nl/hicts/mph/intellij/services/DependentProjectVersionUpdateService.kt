package nl.hicts.mph.intellij.services

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import nl.hicts.mph.intellij.model.DependentProjectsAnalysis
import nl.hicts.mph.intellij.model.MavenCoordinates

data class DependentProjectsUpdateResult(
    val updatedProjectCount: Int,
    val updatedReferenceCount: Int,
    val unchangedProjectCount: Int,
    val issues: List<String>,
)

@Service(Service.Level.PROJECT)
class DependentProjectVersionUpdateService(
    private val project: Project,
) {
    fun update(analysis: DependentProjectsAnalysis): DependentProjectsUpdateResult {
        val groupId = analysis.target.groupId?.takeIf(String::isNotBlank)
            ?: return failedResult(analysis, "The selected project has no resolved groupId.")
        val newVersion = analysis.target.version?.takeIf(String::isNotBlank)
            ?: return failedResult(analysis, "The selected project has no resolved version.")
        val target = MavenCoordinates(groupId, analysis.target.artifactId)
        var updatedProjects = 0
        var updatedReferences = 0
        var unchangedProjects = 0
        val issues = mutableListOf<String>()

        WriteCommandAction.writeCommandAction(project)
            .withName("Update dependent Maven versions")
            .withGlobalUndo()
            .run<RuntimeException> {
                analysis.dependents.forEach { dependent ->
                    val virtualFile = LocalFileSystem.getInstance()
                        .refreshAndFindFileByPath(dependent.project.pomPath)
                    val document = virtualFile?.let(FileDocumentManager.getInstance()::getDocument)
                    if (virtualFile == null || document == null) {
                        unchangedProjects++
                        issues += "${dependent.project.artifactId}: pom.xml is unavailable or not writable."
                        return@forEach
                    }

                    val update = PomReferenceVersionEditor.update(
                        document.text,
                        target,
                        newVersion,
                        dependent.references,
                    )
                    if (update.changed) {
                        document.setText(update.content)
                        FileDocumentManager.getInstance().saveDocument(document)
                        updatedProjects++
                        updatedReferences += update.updatedReferenceCount
                    } else {
                        unchangedProjects++
                    }
                    update.unresolvedProperties.forEach { property ->
                        issues += "${dependent.project.artifactId}: property '$property' is inherited or missing."
                    }
                    if (update.missingVersionCount > 0) {
                        issues += "${dependent.project.artifactId}: ${update.missingVersionCount} reference(s) have no local version."
                    }
                }
            }

        return DependentProjectsUpdateResult(
            updatedProjectCount = updatedProjects,
            updatedReferenceCount = updatedReferences,
            unchangedProjectCount = unchangedProjects,
            issues = issues,
        )
    }

    private fun failedResult(analysis: DependentProjectsAnalysis, issue: String) =
        DependentProjectsUpdateResult(0, 0, analysis.dependents.size, listOf(issue))
}
