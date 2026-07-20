package nl.hicts.mph.intellij.services

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import nl.hicts.mph.intellij.model.MavenCoordinates
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.model.MavenReferenceKind

enum class BulkVersionMode(val displayName: String) {
    ADD_PREFIX("Add prefix"),
    REMOVE_PREFIX("Remove prefix"),
}

data class BulkVersionUpdateRequest(
    val selectedProjects: List<MavenProjectInfo>,
    val workspaceProjects: List<MavenProjectInfo>,
    val prefix: String,
    val mode: BulkVersionMode,
    val updateDependents: Boolean,
    val normalizePrefix: Boolean = false,
) {
    constructor(
        selectedProjects: List<MavenProjectInfo>,
        workspaceProjects: List<MavenProjectInfo>,
        prefix: String,
        mode: BulkVersionMode,
        updateDependents: Boolean,
    ) : this(selectedProjects, workspaceProjects, prefix, mode, updateDependents, false)
}

data class BulkVersionUpdateResult(
    val updatedProjectCount: Int,
    val updatedReferenceCount: Int,
    val unchangedProjectCount: Int,
    val issues: List<String>,
)

@Service(Service.Level.PROJECT)
class BulkVersionUpdateService(
    private val project: Project,
) {
    fun update(request: BulkVersionUpdateRequest): BulkVersionUpdateResult {
        require(request.selectedProjects.isNotEmpty()) { "Select at least one Maven project." }
        require(request.prefix.isNotBlank()) { "Enter a version prefix." }

        val fileDocumentManager = FileDocumentManager.getInstance()
        val documents = request.workspaceProjects
            .distinctBy(MavenProjectInfo::pomPath)
            .associateWith { projectInfo ->
                LocalFileSystem.getInstance().refreshAndFindFileByPath(projectInfo.pomPath)
                    ?.let(fileDocumentManager::getDocument)
            }
        val selected = request.selectedProjects.distinctBy(MavenProjectInfo::pomPath)
        val selectedPaths = selected.map(MavenProjectInfo::pomPath).toSet()
        val issues = mutableListOf<String>()
        val targetVersions = selected.mapNotNull { projectInfo ->
            val document = documents[projectInfo]
            val currentVersion = document?.text?.let(PomReferenceVersionEditor::findProjectVersion)
            if (document == null) {
                issues += "${projectInfo.artifactId}: pom.xml is unavailable or not writable."
                return@mapNotNull null
            }
            if (currentVersion.isNullOrBlank()) {
                issues += "${projectInfo.artifactId}: the project version is inherited or unresolved."
                return@mapNotNull null
            }
            val newVersion = if (request.normalizePrefix) {
                request.prefix + currentVersion.removePrefix(request.prefix)
            } else {
                when (request.mode) {
                    BulkVersionMode.ADD_PREFIX -> request.prefix + currentVersion
                    BulkVersionMode.REMOVE_PREFIX -> currentVersion.removePrefix(request.prefix)
                }
            }
            projectInfo to newVersion
        }

        var updatedReferences = 0
        val changedPaths = linkedSetOf<String>()
        WriteCommandAction.writeCommandAction(project)
            .withName("Bulk update Maven versions")
            .withGlobalUndo()
            .run<RuntimeException> {
                documents.forEach { (projectInfo, document) ->
                    if (document == null) return@forEach
                    var content = document.text

                    targetVersions.firstOrNull { (target, _) -> target.pomPath == projectInfo.pomPath }
                        ?.let { (_, newVersion) ->
                            val update = PomReferenceVersionEditor.updateProjectVersion(content, newVersion)
                            content = update.content
                            update.unresolvedProperty?.let { property ->
                                issues += "${projectInfo.artifactId}: project version property '$property' is inherited or missing."
                            }
                        }

                    if (request.updateDependents) {
                        targetVersions.forEach { (target, newVersion) ->
                            val groupId = target.groupId?.takeIf(String::isNotBlank)
                            if (groupId == null) {
                                if (target.pomPath == projectInfo.pomPath) {
                                    issues += "${target.artifactId}: groupId is unresolved; dependent references were not updated."
                                }
                                return@forEach
                            }
                            val update = PomReferenceVersionEditor.update(
                                content,
                                MavenCoordinates(groupId, target.artifactId),
                                newVersion,
                                MavenReferenceKind.entries.toSet(),
                            )
                            content = update.content
                            updatedReferences += update.updatedReferenceCount
                            update.unresolvedProperties.forEach { property ->
                                issues += "${projectInfo.artifactId}: property '$property' is inherited or missing."
                            }
                            if (update.missingVersionCount > 0) {
                                issues += "${projectInfo.artifactId}: ${update.missingVersionCount} reference(s) have no local version."
                            }
                        }
                    }

                    if (content != document.text) {
                        document.setText(content)
                        fileDocumentManager.saveDocument(document)
                        changedPaths += projectInfo.pomPath
                    }
                }
            }

        return BulkVersionUpdateResult(
            updatedProjectCount = selectedPaths.count(changedPaths::contains),
            updatedReferenceCount = updatedReferences,
            unchangedProjectCount = selectedPaths.size - selectedPaths.count(changedPaths::contains),
            issues = issues.distinct(),
        )
    }
}
