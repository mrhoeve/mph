package nl.hicts.mph.intellij.services

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.editor.Document
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
        val targetVersions = targetVersions(request, selected, documents, issues)

        var updatedReferences = 0
        val changedPaths = linkedSetOf<String>()
        WriteCommandAction.writeCommandAction(project)
            .withName("Bulk update Maven versions")
            .withGlobalUndo()
            .run<RuntimeException> {
                documents.forEach { (projectInfo, document) ->
                    if (document == null) return@forEach
                    val update = updateDocument(projectInfo, document.text, targetVersions, request.updateDependents, issues)
                    val content = update.content
                    updatedReferences += update.updatedReferences

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

    private fun targetVersions(
        request: BulkVersionUpdateRequest,
        selected: List<MavenProjectInfo>,
        documents: Map<MavenProjectInfo, Document?>,
        issues: MutableList<String>,
    ): List<TargetVersion> = selected.mapNotNull { projectInfo ->
        val document = documents[projectInfo]
        if (document == null) {
            issues += "${projectInfo.artifactId}: pom.xml is unavailable or not writable."
            return@mapNotNull null
        }
        val currentVersion = PomReferenceVersionEditor.findProjectVersion(document.text)
        if (currentVersion.isNullOrBlank()) {
            issues += "${projectInfo.artifactId}: the project version is inherited or unresolved."
            return@mapNotNull null
        }
        TargetVersion(projectInfo, adjustedVersion(request, currentVersion))
    }

    private fun adjustedVersion(request: BulkVersionUpdateRequest, currentVersion: String): String =
        if (request.normalizePrefix) {
            request.prefix + currentVersion.removePrefix(request.prefix)
        } else {
            when (request.mode) {
                BulkVersionMode.ADD_PREFIX -> request.prefix + currentVersion
                BulkVersionMode.REMOVE_PREFIX -> currentVersion.removePrefix(request.prefix)
            }
        }

    private fun updateDocument(
        projectInfo: MavenProjectInfo,
        originalContent: String,
        targets: List<TargetVersion>,
        updateDependents: Boolean,
        issues: MutableList<String>,
    ): DocumentUpdate {
        var content = originalContent
        targets.firstOrNull { it.project.pomPath == projectInfo.pomPath }?.let { target ->
            val update = PomReferenceVersionEditor.updateProjectVersion(content, target.version)
            content = update.content
            update.unresolvedProperty?.let { property ->
                issues += "${projectInfo.artifactId}: project version property '$property' is inherited or missing."
            }
        }
        if (!updateDependents) return DocumentUpdate(content, 0)

        var updatedReferences = 0
        targets.forEach { target ->
            val update = updateReference(content, projectInfo, target, issues) ?: return@forEach
            content = update.content
            updatedReferences += update.updatedReferenceCount
        }
        return DocumentUpdate(content, updatedReferences)
    }

    private fun updateReference(
        content: String,
        projectInfo: MavenProjectInfo,
        target: TargetVersion,
        issues: MutableList<String>,
    ): PomReferenceUpdate? {
        val groupId = target.project.groupId?.takeIf(String::isNotBlank)
        if (groupId == null) {
            if (target.project.pomPath == projectInfo.pomPath) {
                issues += "${target.project.artifactId}: groupId is unresolved; dependent references were not updated."
            }
            return null
        }
        val update = PomReferenceVersionEditor.update(
            content,
            MavenCoordinates(groupId, target.project.artifactId),
            target.version,
            MavenReferenceKind.entries.toSet(),
        )
        update.unresolvedProperties.forEach { property ->
            issues += "${projectInfo.artifactId}: property '$property' is inherited or missing."
        }
        if (update.missingVersionCount > 0) {
            issues += "${projectInfo.artifactId}: ${update.missingVersionCount} reference(s) have no local version."
        }
        return update
    }

    private data class TargetVersion(val project: MavenProjectInfo, val version: String)
    private data class DocumentUpdate(val content: String, val updatedReferences: Int)
}
