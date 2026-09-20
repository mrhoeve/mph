package nl.hicts.mph.intellij.services

import java.nio.file.Files
import java.nio.file.Path
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
    SET_VERSION("Set explicit version"),
    KEEP_CURRENT("Realign current versions"),
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
    fun update(request: BulkVersionUpdateRequest, owner: WorkspaceOperationCoordinator.Lease? = null): BulkVersionUpdateResult =
        WorkspaceOperationCoordinator.run("Version update", owner) { applyOwned(prepare(request)) }

    fun prepare(request: BulkVersionUpdateRequest): VersionAlignmentPlan {
        require(request.selectedProjects.isNotEmpty()) { "Select at least one Maven project." }
        require(request.mode == BulkVersionMode.KEEP_CURRENT || request.prefix.isNotBlank()) {
            "Enter a version or prefix."
        }
        val manager = FileDocumentManager.getInstance()
        val projects = (request.workspaceProjects + request.selectedProjects).distinctBy { it.pomPath }
        val inputs = projects.map { info ->
            val path = Path.of(info.pomPath)
            require(Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "POM is missing or is a symbolic link: $path" }
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                ?: error("POM is unavailable: $path")
            val document = manager.getDocument(file) ?: error("POM cannot be read: $path")
            AlignmentInput(info, document.text, Files.readAllBytes(path))
        }
        val documents = projects.associateWith { info ->
            LocalFileSystem.getInstance().findFileByPath(info.pomPath.replace('\\', '/'))?.let(manager::getDocument)
        }
        val selected = request.selectedProjects.distinctBy { it.pomPath }
        val issues = mutableListOf<String>()
        val targets = targetVersions(request, selected, documents, issues)
        var references = 0
        val edits = inputs.mapNotNull { input ->
            val update = updateDocument(input.project, input.content, targets, request.updateDependents, issues)
            references += update.updatedReferences
            if (update.content == input.content) null else {
                validatePom(update.content)
                AlignmentEdit(input.project, input.content, update.content)
            }
        }
        val changed = edits.map { it.project.pomPath }.toSet()
        val updated = selected.count { it.pomPath in changed }
        return VersionAlignmentPlan(inputs, edits, BulkVersionUpdateResult(updated, references, selected.size - updated, issues.distinct()))
    }

    fun apply(plan: VersionAlignmentPlan, owner: WorkspaceOperationCoordinator.Lease? = null): BulkVersionUpdateResult =
        WorkspaceOperationCoordinator.run("Version update", owner) { applyOwned(plan) }

    internal fun applyOwned(
        plan: VersionAlignmentPlan,
        save: (Document) -> Unit = FileDocumentManager.getInstance()::saveDocument,
    ): BulkVersionUpdateResult {
        val manager = FileDocumentManager.getInstance()
        val documents = plan.inputs.associate { input ->
            val path = Path.of(input.project.pomPath)
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: error("POM disappeared: $path")
            val document = manager.getDocument(file) ?: error("POM cannot be read: $path")
            check(Files.readAllBytes(path).contentEquals(input.diskBytes) && document.text == input.content) {
                "POM changed after the preview. Review alignment again: $path"
            }
            if (plan.edits.any { it.project.pomPath == input.project.pomPath }) {
                check(file.isWritable && document.isWritable && Files.isWritable(path)) { "POM is read-only: $path" }
            }
            input.project.pomPath to document
        }
        if (plan.edits.isEmpty()) return plan.result
        val backup = AlignmentRecoveryStore.capture(plan.inputs)
        val touched = mutableListOf<String>()
        WriteCommandAction.writeCommandAction(project).withName("Apply reviewed Maven alignment").withGlobalUndo().run<RuntimeException> {
            try {
                // Validate every input again inside the write action before changing any document.
                plan.inputs.forEach { input ->
                    check(documents.getValue(input.project.pomPath).text == input.content &&
                        Files.readAllBytes(Path.of(input.project.pomPath)).contentEquals(input.diskBytes)) {
                        "POM changed before alignment: ${input.project.pomPath}"
                    }
                }
                plan.edits.forEach { edit ->
                    val document = documents.getValue(edit.project.pomPath)
                    touched += edit.project.pomPath
                    document.setText(edit.after)
                    save(document)
                    check(!manager.isDocumentUnsaved(document)) { "POM could not be saved: ${edit.project.pomPath}" }
                }
            } catch (error: Exception) {
                throw AlignmentApplyException(
                    "Alignment stopped. Files possibly changed: ${touched.joinToString().ifEmpty { "none" }}. " +
                        "Review Git status and use Undo or recovery copies at $backup. ${error.message}", error,
                )
            }
        }
        return plan.result
    }

    private fun validatePom(content: String) {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        val xml = factory.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(content)))
        require(xml.documentElement.tagName == "project") { "The updated file is not a Maven POM." }
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
        val localVersion = PomReferenceVersionEditor.findProjectVersion(document.text)
        val currentVersion = localVersion ?: request.takeIf { it.mode == BulkVersionMode.KEEP_CURRENT }
            ?.let { projectInfo.version }
        if (currentVersion.isNullOrBlank()) {
            issues += "${projectInfo.artifactId}: the project version is inherited or unresolved."
            return@mapNotNull null
        }
        TargetVersion(
            project = projectInfo,
            version = adjustedVersion(request, currentVersion),
            updateProjectVersion = localVersion != null,
        )
    }

    private fun adjustedVersion(request: BulkVersionUpdateRequest, currentVersion: String): String =
        if (request.normalizePrefix) {
            request.prefix + currentVersion.removePrefix(request.prefix)
        } else {
            when (request.mode) {
                BulkVersionMode.ADD_PREFIX -> request.prefix + currentVersion
                BulkVersionMode.REMOVE_PREFIX -> currentVersion.removePrefix(request.prefix)
                BulkVersionMode.SET_VERSION -> request.prefix
                BulkVersionMode.KEEP_CURRENT -> currentVersion
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
        targets.firstOrNull {
            it.project.pomPath == projectInfo.pomPath && it.updateProjectVersion
        }?.let { target ->
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

    private data class TargetVersion(
        val project: MavenProjectInfo,
        val version: String,
        val updateProjectVersion: Boolean,
    )
    private data class DocumentUpdate(val content: String, val updatedReferences: Int)
}
