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
