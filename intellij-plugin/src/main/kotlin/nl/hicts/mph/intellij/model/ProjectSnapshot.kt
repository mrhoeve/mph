package nl.hicts.mph.intellij.model

data class MavenProjectDescriptor(
    val groupId: String?,
    val artifactId: String,
    val version: String?,
    val pomPath: String,
)

data class MavenProjectInfo(
    val groupId: String?,
    val artifactId: String,
    val version: String?,
    val pomPath: String,
    val gitRootPath: String?,
) {
    val coordinates: String
        get() = listOfNotNull(groupId, artifactId.takeIf(String::isNotBlank)).joinToString(":")
}

data class GitProjectGroup(
    val rootPath: String?,
    val projects: List<MavenProjectInfo>,
)

data class ProjectSnapshot(
    val groups: List<GitProjectGroup>,
) {
    val projectCount: Int
        get() = groups.sumOf { it.projects.size }

    val repositoryCount: Int
        get() = groups.count { it.rootPath != null }
}

data class MavenCoordinates(
    val groupId: String,
    val artifactId: String,
)

enum class MavenReferenceKind(val displayName: String) {
    PARENT("Parent"),
    DEPENDENCY("Dependency"),
    MANAGED_DEPENDENCY("Managed dependency"),
}

data class MavenProjectDependencyDescriptor(
    val project: MavenProjectInfo,
    val parent: MavenCoordinates?,
    val dependencies: Set<MavenCoordinates>,
    val managedDependencies: Set<MavenCoordinates>,
)

data class DependentMavenProject(
    val project: MavenProjectInfo,
    val references: Set<MavenReferenceKind>,
)

data class DependentProjectsAnalysis(
    val target: MavenProjectInfo,
    val dependents: List<DependentMavenProject>,
)
