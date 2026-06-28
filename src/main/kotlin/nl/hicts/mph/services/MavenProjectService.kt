package nl.hicts.mph.services

import nl.hicts.mph.models.MavenProject
import nl.hicts.mph.models.getAppropiateArtifactId
import nl.hicts.mph.models.getAppropiateGroupId
import nl.hicts.mph.models.getAppropiateVersion
import org.apache.maven.model.Model
import org.apache.maven.model.io.xpp3.MavenXpp3Writer
import org.springframework.stereotype.Service
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

@Service
class MavenProjectService {

    fun scanAndAnalyze(basePath: Path, maxDepth: Int): List<ProjectAnalysis> {
        val rootProjects = ScanProjectUtil.searchAllMavenProjects(basePath.toFile(), maxDepth)
        val allProjects = flattenProjects(rootProjects)

        return rootProjects.map { analyzeProject(it, allProjects) }.sortedBy { it.artifactId }
    }

    fun updateVersions(basePath: Path, maxDepth: Int, groupId: String, artifactId: String, newVersion: String) {
        val rootProjects = ScanProjectUtil.searchAllMavenProjects(basePath.toFile(), maxDepth)
        val allProjects = flattenProjects(rootProjects)

        updateProjectsDependents(allProjects, mapOf(Pair(groupId, artifactId) to newVersion))
    }

    fun bulkUpdateVersions(
        basePath: Path,
        maxDepth: Int,
        rootProjectPaths: List<String>,
        prefix: String,
        updateDependents: Boolean,
        mode: String = "ADD_PREFIX"
    ) {
        val rootProjects = ScanProjectUtil.searchAllMavenProjects(basePath.toFile(), maxDepth)
        val allProjects = flattenProjects(rootProjects)

        val versionMap = mutableMapOf<Pair<String, String>, String>()

        // 1. Update the selected projects and their modules
        for (rootPath in rootProjectPaths) {
            val normalizedRootPath = Paths.get(rootPath).toAbsolutePath().normalize().toString()
            val rootProject = allProjects.find { 
                Paths.get(it.pomLocation.absolutePath).toAbsolutePath().normalize().toString() == normalizedRootPath 
            } ?: continue
            val projectsToUpdate = flattenProjects(listOf(rootProject))

            for (project in projectsToUpdate) {
                val oldVersion = project.version()
                val newVersion = if (mode == "REMOVE_PREFIX") {
                    if (oldVersion.startsWith(prefix)) {
                        oldVersion.substring(prefix.length)
                    } else {
                        oldVersion
                    }
                } else {
                    prefix + oldVersion
                }
                
                val groupId = project.groupId()
                val artifactId = project.artifactId()

                val model = project.model
                model.version = newVersion
                savePOM(project.pomLocation, model)

                versionMap[Pair(groupId, artifactId)] = newVersion
            }
        }

        // 2. Update dependents if requested
        if (updateDependents && versionMap.isNotEmpty()) {
            updateProjectsDependents(allProjects, versionMap)
        }
    }

    private fun updateProjectsDependents(allProjects: List<MavenProject>, versionMap: Map<Pair<String, String>, String>) {
        for (project in allProjects) {
            var modified = false
            val model = project.model

            // Update parent
            if (model.parent != null) {
                val key = Pair(model.parent.groupId, model.parent.artifactId)
                if (versionMap.containsKey(key)) {
                    model.parent.version = versionMap[key]
                    modified = true
                }
            }

            // Update dependencies
            model.dependencies?.forEach { dep ->
                val key = Pair(dep.groupId, dep.artifactId)
                if (versionMap.containsKey(key)) {
                    val newVersion = versionMap[key]!!
                    if (dep.version != null && dep.version.startsWith("\${")) {
                        val propName = dep.version.substring(2, dep.version.length - 1)
                        if (model.properties.containsKey(propName)) {
                            model.properties.setProperty(propName, newVersion)
                            modified = true
                        }
                    } else if (dep.version != null) {
                        dep.version = newVersion
                        modified = true
                    }
                }
            }

            // Update dependency management
            model.dependencyManagement?.dependencies?.forEach { dep ->
                val key = Pair(dep.groupId, dep.artifactId)
                if (versionMap.containsKey(key)) {
                    val newVersion = versionMap[key]!!
                    if (dep.version != null && dep.version.startsWith("\${")) {
                        val propName = dep.version.substring(2, dep.version.length - 1)
                        if (model.properties.containsKey(propName)) {
                            model.properties.setProperty(propName, newVersion)
                            modified = true
                        }
                    } else if (dep.version != null) {
                        dep.version = newVersion
                        modified = true
                    }
                }
            }

            // Also check properties directly for pattern artifactId.version
            versionMap.forEach { (key, newVersion) ->
                val artifactId = key.second
                val directPropName = "$artifactId.version"
                if (model.properties.containsKey(directPropName)) {
                    model.properties.setProperty(directPropName, newVersion)
                    modified = true
                }
            }

            if (modified) {
                savePOM(project.pomLocation, model)
            }
        }
    }

    private fun savePOM(file: File, model: Model) {
        val writer = MavenXpp3Writer()
        Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8).use { writerStream ->
            writer.write(writerStream, model)
        }
    }

    private fun flattenProjects(projects: List<MavenProject>): List<MavenProject> {
        val result = mutableListOf<MavenProject>()
        for (project in projects) {
            result.add(project)
            result.addAll(flattenProjects(project.modules))
        }
        return result
    }

    private fun analyzeProject(project: MavenProject, allProjects: List<MavenProject>): ProjectAnalysis {
        val groupId = project.groupId()
        val artifactId = project.artifactId()
        val version = project.version()
        
        val usages = findUsages(project, allProjects)
        
        val hasSpringBootParent = project.model.parent?.artifactId?.contains("spring-boot") == true

        return ProjectAnalysis(
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            path = project.pomLocation.absolutePath,
            modules = project.modules.map { analyzeProject(it, allProjects) }.sortedBy { it.artifactId },
            usages = usages,
            hasSpringBootParent = hasSpringBootParent
        )
    }

    private fun findUsages(targetProject: MavenProject, allProjects: List<MavenProject>): List<ProjectUsage> {
        val groupId = targetProject.groupId()
        val artifactId = targetProject.artifactId()
        val targetRootPath = targetProject.rootPomPath()

        val usages = mutableListOf<ProjectUsage>()
        for (proj in allProjects) {
            // Skip if it's the same project or within the same root project
            if (proj.rootPomPath() == targetRootPath) continue

            val model = proj.model
            
            // Avoid counting itself as a usage if it's just the declaration
            // But actually we want to see where it's used as a dependency.
            
            var usedVersion: String? = null

            // Check parent
            if (model.parent?.groupId == groupId && model.parent?.artifactId == artifactId) {
                usedVersion = model.parent.version
            }

            // Check dependencies
            if (usedVersion == null) {
                model.dependencies?.forEach { dep ->
                    if (dep.groupId == groupId && dep.artifactId == artifactId) {
                        usedVersion = dep.version ?: "managed"
                    }
                }
            }

            // Check dependency management
            if (usedVersion == null) {
                model.dependencyManagement?.dependencies?.forEach { dep ->
                    if (dep.groupId == groupId && dep.artifactId == artifactId) {
                        usedVersion = dep.version ?: "managed"
                    }
                }
            }

            if (usedVersion != null) {
                // Resolve property if usedVersion is a property
                val resolvedVersion = if (usedVersion!!.startsWith("\${")) {
                    val propName = usedVersion!!.substring(2, usedVersion!!.length - 1)
                    model.properties.getProperty(propName) ?: usedVersion!!
                } else {
                    usedVersion!!
                }

                usages.add(ProjectUsage(
                    usedInGroupId = proj.groupId(),
                    usedInArtifactId = proj.artifactId(),
                    usedVersion = resolvedVersion,
                    path = proj.pomLocation.absolutePath
                ))
            }
        }
        return usages
    }
}

private fun MavenProject.groupId() = this.getAppropiateGroupId().value
private fun MavenProject.artifactId() = this.getAppropiateArtifactId().value
private fun MavenProject.version() = this.getAppropiateVersion().value

private fun MavenProject.rootPomPath(): String {
    var current = this
    while (current.moduleWithinProject != null) {
        current = current.moduleWithinProject!!
    }
    return current.pomLocation.absolutePath
}

data class ProjectAnalysis(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val path: String,
    val modules: List<ProjectAnalysis>,
    val usages: List<ProjectUsage>,
    val hasSpringBootParent: Boolean = false
)

data class ProjectUsage(
    val usedInGroupId: String,
    val usedInArtifactId: String,
    val usedVersion: String,
    val path: String
)
