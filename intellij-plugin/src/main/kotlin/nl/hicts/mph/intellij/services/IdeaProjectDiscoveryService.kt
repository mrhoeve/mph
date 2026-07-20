package nl.hicts.mph.intellij.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager
import nl.hicts.mph.intellij.model.DependentProjectsAnalysis
import nl.hicts.mph.intellij.model.DependentProjectsAnalyzer
import nl.hicts.mph.intellij.model.MavenCoordinates
import nl.hicts.mph.intellij.model.MavenProjectDescriptor
import nl.hicts.mph.intellij.model.MavenProjectDependencyDescriptor
import nl.hicts.mph.intellij.model.ProjectSnapshot
import nl.hicts.mph.intellij.model.ProjectSnapshotBuilder
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class IdeaProjectDiscoveryService(
    private val project: Project,
) {
    fun discover(): ProjectSnapshot {
        val mavenProjects = MavenProjectsManager.getInstance(project).projects
        return ProjectSnapshotBuilder().build(
            mavenProjects.map { mavenProject ->
                MavenProjectDescriptor(
                    groupId = mavenProject.mavenId.groupId,
                    artifactId = mavenProject.mavenId.artifactId ?: mavenProject.displayName,
                    version = mavenProject.mavenId.version,
                    pomPath = mavenProject.file.path,
                )
            },
            gitRootPaths(),
        )
    }

    fun findDependents(targetPomPath: String, targetPomContent: String? = null): DependentProjectsAnalysis? {
        val descriptors = dependencyDescriptors()
        val analysis = DependentProjectsAnalyzer().analyze(targetPomPath, descriptors) ?: return null
        val documentVersion = targetPomContent?.let(PomReferenceVersionEditor::findProjectVersion)
        return if (documentVersion == null) {
            analysis
        } else {
            analysis.copy(target = analysis.target.copy(version = documentVersion))
        }
    }

    fun dependencyDescriptors(): List<MavenProjectDependencyDescriptor> {
        val mavenProjects = MavenProjectsManager.getInstance(project).projects
        val projectInfos = ProjectSnapshotBuilder().build(
            mavenProjects.map { mavenProject ->
                MavenProjectDescriptor(
                    groupId = mavenProject.mavenId.groupId,
                    artifactId = mavenProject.mavenId.artifactId ?: mavenProject.displayName,
                    version = mavenProject.mavenId.version,
                    pomPath = mavenProject.file.path,
                )
            },
            gitRootPaths(),
        ).groups.flatMap { it.projects }.associateBy { normalizedPath(it.pomPath) }

        return mavenProjects.mapNotNull { mavenProject ->
            val projectInfo = projectInfos[normalizedPath(mavenProject.file.path)] ?: return@mapNotNull null
            MavenProjectDependencyDescriptor(
                project = projectInfo,
                parent = coordinates(mavenProject.parentId?.groupId, mavenProject.parentId?.artifactId),
                dependencies = mavenProject.dependencies.mapNotNull { dependency ->
                    coordinates(dependency.groupId, dependency.artifactId)
                }.toSet(),
                managedDependencies = mavenProject.managedDependencies().values.mapNotNull { dependency ->
                    coordinates(dependency.groupId, dependency.artifactId)
                }.toSet(),
            )
        }
    }

    private fun gitRootPaths(): List<String> =
        GitRepositoryManager.getInstance(project).repositories.map { repository ->
            repository.root.path
        }

    private fun coordinates(groupId: String?, artifactId: String?): MavenCoordinates? =
        groupId?.takeIf(String::isNotBlank)?.let { group ->
            artifactId?.takeIf(String::isNotBlank)?.let { artifact -> MavenCoordinates(group, artifact) }
        }

    private fun normalizedPath(path: String): Path = Path.of(path).toAbsolutePath().normalize()
}
