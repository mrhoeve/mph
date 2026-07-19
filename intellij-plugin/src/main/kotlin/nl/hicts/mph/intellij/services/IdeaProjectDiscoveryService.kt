package nl.hicts.mph.intellij.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager
import nl.hicts.mph.intellij.model.MavenProjectDescriptor
import nl.hicts.mph.intellij.model.ProjectSnapshot
import nl.hicts.mph.intellij.model.ProjectSnapshotBuilder
import org.jetbrains.idea.maven.project.MavenProjectsManager

@Service(Service.Level.PROJECT)
class IdeaProjectDiscoveryService(
    private val project: Project,
) {
    fun discover(): ProjectSnapshot {
        val mavenProjects = MavenProjectsManager.getInstance(project).projects.map { mavenProject ->
            MavenProjectDescriptor(
                groupId = mavenProject.mavenId.groupId,
                artifactId = mavenProject.mavenId.artifactId ?: mavenProject.displayName,
                version = mavenProject.mavenId.version,
                pomPath = mavenProject.file.path,
            )
        }

        val gitRoots = GitRepositoryManager.getInstance(project).repositories.map { repository ->
            repository.root.path
        }

        return ProjectSnapshotBuilder().build(mavenProjects, gitRoots)
    }
}
