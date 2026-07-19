package nl.hicts.mph.intellij.model

import java.nio.file.Path

class ProjectSnapshotBuilder {
    fun build(
        projects: Collection<MavenProjectDescriptor>,
        gitRootPaths: Collection<String>,
    ): ProjectSnapshot {
        val normalizedRoots = gitRootPaths
            .map(::normalizedPath)
            .distinct()
            .sortedByDescending { it.nameCount }

        val projectInfos = projects.map { descriptor ->
            val pomPath = normalizedPath(descriptor.pomPath)
            val gitRoot = normalizedRoots.firstOrNull(pomPath::startsWith)

            MavenProjectInfo(
                groupId = descriptor.groupId,
                artifactId = descriptor.artifactId,
                version = descriptor.version,
                pomPath = pomPath.toString(),
                gitRootPath = gitRoot?.toString(),
            )
        }

        val groups = projectInfos
            .groupBy(MavenProjectInfo::gitRootPath)
            .map { (rootPath, groupedProjects) ->
                GitProjectGroup(
                    rootPath = rootPath,
                    projects = groupedProjects.sortedWith(
                        compareBy(MavenProjectInfo::artifactId, MavenProjectInfo::pomPath),
                    ),
                )
            }
            .sortedWith(
                compareBy<GitProjectGroup> { it.rootPath == null }
                    .thenBy { it.rootPath.orEmpty() },
            )

        return ProjectSnapshot(groups)
    }

    private fun normalizedPath(path: String): Path = Path.of(path).toAbsolutePath().normalize()
}
