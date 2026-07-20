package nl.hicts.mph.intellij.model

import java.nio.file.Path

class DependentProjectsAnalyzer {
    fun analyze(
        targetPomPath: String,
        projects: Collection<MavenProjectDependencyDescriptor>,
    ): DependentProjectsAnalysis? {
        val normalizedTargetPath = normalizedPath(targetPomPath)
        val target = projects.firstOrNull { normalizedPath(it.project.pomPath) == normalizedTargetPath }
            ?: return null
        val coordinates = target.project.groupId
            ?.takeIf(String::isNotBlank)
            ?.let { MavenCoordinates(it, target.project.artifactId) }

        val dependents = if (coordinates == null) {
            emptyList()
        } else {
            projects.asSequence()
                .filterNot { normalizedPath(it.project.pomPath) == normalizedTargetPath }
                .mapNotNull { descriptor -> dependentProject(descriptor, coordinates) }
                .sortedWith(compareBy({ it.project.artifactId }, { it.project.pomPath }))
                .toList()
        }

        return DependentProjectsAnalysis(target.project, dependents)
    }

    private fun dependentProject(
        descriptor: MavenProjectDependencyDescriptor,
        target: MavenCoordinates,
    ): DependentMavenProject? {
        val references = buildSet {
            if (descriptor.parent == target) add(MavenReferenceKind.PARENT)
            if (target in descriptor.dependencies) add(MavenReferenceKind.DEPENDENCY)
            if (target in descriptor.managedDependencies) add(MavenReferenceKind.MANAGED_DEPENDENCY)
        }
        return references.takeIf(Set<MavenReferenceKind>::isNotEmpty)?.let {
            DependentMavenProject(descriptor.project, it)
        }
    }

    private fun normalizedPath(path: String): Path = Path.of(path).toAbsolutePath().normalize()
}
