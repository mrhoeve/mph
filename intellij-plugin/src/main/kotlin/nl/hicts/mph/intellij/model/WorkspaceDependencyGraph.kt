package nl.hicts.mph.intellij.model

import java.nio.file.Path

enum class DependencyGraphEdgeKind {
    DEPENDENCY,
    MODULE,
}

data class DependencyGraphNode(
    val id: String,
    val project: MavenProjectInfo,
    val buildStep: Int,
    val repositoryName: String,
    val parentId: String? = null,
    val isRepositoryRoot: Boolean = false,
    val partOfCycle: Boolean = false,
)

data class DependencyGraphEdge(
    val sourceId: String,
    val targetId: String,
    val kind: DependencyGraphEdgeKind,
)

data class WorkspaceDependencyGraph(
    val nodes: List<DependencyGraphNode>,
    val edges: List<DependencyGraphEdge>,
    val focusedNodeId: String? = null,
) {
    fun focus(nodeId: String?): WorkspaceDependencyGraph {
        if (nodeId == null) return copy(focusedNodeId = null)
        val target = nodes.firstOrNull { it.id == nodeId } ?: return this
        val included = linkedSetOf(target.id)
        includeDescendants(target.id, included)
        val focusAndDescendants = included.toSet()
        edges.filter { it.kind == DependencyGraphEdgeKind.DEPENDENCY }.forEach { edge ->
            if (edge.sourceId in focusAndDescendants || edge.targetId in focusAndDescendants) {
                included += edge.sourceId
                included += edge.targetId
            }
        }
        var parent = target.parentId
        while (parent != null) {
            included += parent
            parent = nodes.firstOrNull { it.id == parent }?.parentId
        }
        nodes.filter { it.id in included }.forEach { node ->
            var ancestor = node.parentId
            while (ancestor != null) {
                included += ancestor
                ancestor = nodes.firstOrNull { it.id == ancestor }?.parentId
            }
        }
        return WorkspaceDependencyGraph(
            nodes.filter { it.id in included },
            edges.filter { it.sourceId in included && it.targetId in included },
            nodeId,
        )
    }

    private fun includeDescendants(parentId: String, included: MutableSet<String>) {
        nodes.filter { it.parentId == parentId }.forEach { child ->
            if (included.add(child.id)) includeDescendants(child.id, included)
        }
    }
}

class WorkspaceDependencyGraphBuilder {
    fun build(
        descriptors: Collection<MavenProjectDependencyDescriptor>,
        order: WorkspaceBuildOrder,
    ): WorkspaceDependencyGraph {
        val descriptorList = descriptors.distinctBy { normalized(it.project.pomPath) }
        val byCoordinates = descriptorList.mapNotNull { descriptor ->
            descriptor.project.groupId?.takeIf(String::isNotBlank)?.let { group ->
                MavenCoordinates(group, descriptor.project.artifactId) to descriptor
            }
        }.toMap()
        val orderByRepository = order.entries.associateBy { repositoryPath(it.project) }
        val projectsByRepository = descriptorList.groupBy { repositoryPath(it.project) }
        val ids = descriptorList.associateWith { descriptor -> normalized(descriptor.project.pomPath) }

        val nodes = descriptorList.map { descriptor ->
            val repository = repositoryPath(descriptor.project)
            val projects = projectsByRepository.getValue(repository)
            val root = rootProject(projects, repository)
            val parent = nearestParent(descriptor, projects)
            val orderEntry = orderByRepository[repository]
            DependencyGraphNode(
                id = ids.getValue(descriptor),
                project = descriptor.project,
                buildStep = orderEntry?.buildStep ?: 1,
                repositoryName = Path.of(repository).fileName?.toString() ?: repository,
                parentId = parent?.let(ids::get),
                isRepositoryRoot = descriptor == root,
                partOfCycle = orderEntry?.partOfCycle == true,
            )
        }.sortedWith(compareBy<DependencyGraphNode>({ it.buildStep }, { it.repositoryName }, { it.project.artifactId }))

        val dependencyEdges = descriptorList.flatMap { descriptor ->
            val consumerId = ids.getValue(descriptor)
            relationshipCoordinates(descriptor).mapNotNull { coordinates ->
                byCoordinates[coordinates]?.let { dependency ->
                    DependencyGraphEdge(ids.getValue(dependency), consumerId, DependencyGraphEdgeKind.DEPENDENCY)
                }
            }
        }
        val moduleEdges = nodes.mapNotNull { node ->
            node.parentId?.let { DependencyGraphEdge(it, node.id, DependencyGraphEdgeKind.MODULE) }
        }
        return WorkspaceDependencyGraph(nodes, (dependencyEdges + moduleEdges).distinct())
    }

    private fun relationshipCoordinates(descriptor: MavenProjectDependencyDescriptor): Set<MavenCoordinates> =
        buildSet {
            descriptor.parent?.let(::add)
            addAll(descriptor.dependencies)
            addAll(descriptor.managedDependencies)
        }

    private fun nearestParent(
        descriptor: MavenProjectDependencyDescriptor,
        repositoryProjects: List<MavenProjectDependencyDescriptor>,
    ): MavenProjectDependencyDescriptor? {
        val directory = Path.of(descriptor.project.pomPath).toAbsolutePath().normalize().parent
        return repositoryProjects.asSequence().filterNot(descriptor::equals).filter { candidate ->
            val candidateDirectory = Path.of(candidate.project.pomPath).toAbsolutePath().normalize().parent
            directory.startsWith(candidateDirectory)
        }.maxByOrNull { Path.of(it.project.pomPath).toAbsolutePath().normalize().parent.nameCount }
    }

    private fun rootProject(
        projects: List<MavenProjectDependencyDescriptor>,
        repository: String,
    ): MavenProjectDependencyDescriptor = projects.minBy { descriptor ->
        val root = Path.of(repository).toAbsolutePath().normalize()
        val directory = Path.of(descriptor.project.pomPath).toAbsolutePath().normalize().parent
        if (directory.startsWith(root)) root.relativize(directory).nameCount else directory.nameCount
    }

    private fun repositoryPath(project: MavenProjectInfo): String =
        project.gitRootPath ?: Path.of(project.pomPath).toAbsolutePath().normalize().parent.toString()

    private fun normalized(path: String): String = Path.of(path).toAbsolutePath().normalize().toString()
}
