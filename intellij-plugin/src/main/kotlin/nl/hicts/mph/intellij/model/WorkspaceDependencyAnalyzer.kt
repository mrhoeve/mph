package nl.hicts.mph.intellij.model

import java.nio.file.Path

data class WorkspaceProjectRelationship(
    val coordinates: MavenCoordinates,
    val project: MavenProjectInfo?,
    val kinds: Set<MavenReferenceKind>,
)

data class WorkspaceProjectRelationships(
    val project: MavenProjectInfo,
    val dependencies: List<WorkspaceProjectRelationship>,
    val dependents: List<WorkspaceProjectRelationship>,
)

data class BuildOrderEntry(
    val project: MavenProjectInfo,
    val buildStep: Int,
    val dependsOn: List<String>,
    val partOfCycle: Boolean = false,
)

data class WorkspaceBuildOrder(
    val entries: List<BuildOrderEntry>,
) {
    val hasCycles: Boolean
        get() = entries.any(BuildOrderEntry::partOfCycle)
}

class WorkspaceDependencyAnalyzer {
    fun relationships(
        targetPomPath: String,
        descriptors: Collection<MavenProjectDependencyDescriptor>,
    ): WorkspaceProjectRelationships? {
        val byCoordinates = descriptors.mapNotNull { descriptor ->
            descriptor.project.groupId?.takeIf(String::isNotBlank)?.let { groupId ->
                MavenCoordinates(groupId, descriptor.project.artifactId) to descriptor
            }
        }.toMap()
        val target = descriptors.firstOrNull { normalized(it.project.pomPath) == normalized(targetPomPath) }
            ?: return null
        val targetCoordinates = target.project.groupId?.takeIf(String::isNotBlank)?.let { groupId ->
            MavenCoordinates(groupId, target.project.artifactId)
        }

        val dependencies = relationshipCoordinates(target).map { (coordinates, kinds) ->
            WorkspaceProjectRelationship(coordinates, byCoordinates[coordinates]?.project, kinds)
        }.sortedWith(compareBy({ it.coordinates.artifactId }, { it.coordinates.groupId }))

        val dependents = if (targetCoordinates == null) {
            emptyList()
        } else {
            descriptors.asSequence()
                .filterNot { normalized(it.project.pomPath) == normalized(targetPomPath) }
                .mapNotNull { descriptor ->
                    relationshipCoordinates(descriptor)[targetCoordinates]?.let { kinds ->
                        WorkspaceProjectRelationship(
                            MavenCoordinates(
                                descriptor.project.groupId.orEmpty(),
                                descriptor.project.artifactId,
                            ),
                            descriptor.project,
                            kinds,
                        )
                    }
                }
                .sortedBy { it.project?.artifactId }
                .toList()
        }
        return WorkspaceProjectRelationships(target.project, dependencies, dependents)
    }

    fun buildOrder(descriptors: Collection<MavenProjectDependencyDescriptor>): WorkspaceBuildOrder {
        val repositories = descriptors.groupBy { descriptor ->
            descriptor.project.gitRootPath ?: Path.of(descriptor.project.pomPath).parent.toString()
        }
        val repositoryByCoordinates = mutableMapOf<MavenCoordinates, String>()
        descriptors.forEach { descriptor ->
            descriptor.project.groupId?.takeIf(String::isNotBlank)?.let { groupId ->
                repositoryByCoordinates[MavenCoordinates(groupId, descriptor.project.artifactId)] =
                    descriptor.project.gitRootPath ?: Path.of(descriptor.project.pomPath).parent.toString()
            }
        }

        val dependencies = repositories.keys.associateWith { linkedSetOf<String>() }.toMutableMap()
        descriptors.forEach { descriptor ->
            val repository = descriptor.project.gitRootPath ?: Path.of(descriptor.project.pomPath).parent.toString()
            relationshipCoordinates(descriptor).keys.forEach { coordinates ->
                repositoryByCoordinates[coordinates]
                    ?.takeIf { it != repository }
                    ?.let(dependencies.getValue(repository)::add)
            }
        }

        val remaining = repositories.keys.toMutableSet()
        val resolved = linkedSetOf<String>()
        val stages = mutableListOf<List<String>>()
        while (remaining.isNotEmpty()) {
            val stage = remaining.filter { repository -> dependencies.getValue(repository).all(resolved::contains) }
                .sorted()
            if (stage.isEmpty()) break
            stages += stage
            remaining -= stage.toSet()
            resolved += stage
        }
        if (remaining.isNotEmpty()) stages += remaining.sorted()

        val entries = stages.flatMapIndexed { stageIndex, stage ->
            stage.map { repository ->
                val projects = repositories.getValue(repository)
                val representative = rootProject(projects.map(MavenProjectDependencyDescriptor::project), repository)
                BuildOrderEntry(
                    project = representative,
                    buildStep = stageIndex + 1,
                    dependsOn = dependencies.getValue(repository).mapNotNull { dependencyRoot ->
                        repositories[dependencyRoot]?.let { rootProject(it.map(MavenProjectDependencyDescriptor::project), dependencyRoot) }
                            ?.artifactId
                    }.sorted(),
                    partOfCycle = repository in remaining,
                )
            }
        }
        return WorkspaceBuildOrder(entries)
    }

    private fun relationshipCoordinates(
        descriptor: MavenProjectDependencyDescriptor,
    ): Map<MavenCoordinates, Set<MavenReferenceKind>> {
        val relationships = linkedMapOf<MavenCoordinates, MutableSet<MavenReferenceKind>>()
        descriptor.parent?.let { relationships.getOrPut(it, ::linkedSetOf) += MavenReferenceKind.PARENT }
        descriptor.dependencies.forEach {
            relationships.getOrPut(it, ::linkedSetOf) += MavenReferenceKind.DEPENDENCY
        }
        descriptor.managedDependencies.forEach {
            relationships.getOrPut(it, ::linkedSetOf) += MavenReferenceKind.MANAGED_DEPENDENCY
        }
        return relationships
    }

    private fun rootProject(projects: List<MavenProjectInfo>, repository: String): MavenProjectInfo =
        projects.minByOrNull { project ->
            val repositoryPath = Path.of(repository).toAbsolutePath().normalize()
            val projectDirectory = Path.of(project.pomPath).toAbsolutePath().normalize().parent
            if (projectDirectory.startsWith(repositoryPath)) {
                repositoryPath.relativize(projectDirectory).nameCount
            } else {
                projectDirectory.nameCount
            }
        } ?: error("Repository $repository has no Maven projects.")

    private fun normalized(path: String): Path = Path.of(path).toAbsolutePath().normalize()
}
