package nl.hicts.mph.services

import nl.hicts.mph.models.MavenProject
import nl.hicts.mph.models.getAppropiateArtifactId
import nl.hicts.mph.models.getAppropiateGroupId
import nl.hicts.mph.models.getAppropiateVersion
import org.apache.maven.model.Model
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

data class ManagedProperty(
    val name: String,
    val value: String,
    val inheritedValue: String?,
    val source: String,
    val isOverridden: Boolean,
    val comment: String? = null
)

@Service
class MavenProjectService(
    private val mavenCommandService: MavenCommandService
) {

    private var modelResolver = MavenModelResolver()

    fun scanAndAnalyze(basePath: Path, maxDepth: Int): List<ProjectAnalysis> {
        val rootProjects = ScanProjectUtil.searchAllMavenProjects(basePath.toFile(), maxDepth)
        val allProjects = flattenProjects(rootProjects)
        
        val projectMap = allProjects.associateBy { 
            "${it.getAppropiateGroupId().value}:${it.getAppropiateArtifactId().value}:${it.getAppropiateVersion().value}" 
        }

        // Update model resolver with all found projects to handle multi-module dependencies
        val workspaceMap = allProjects.associate { project ->
            val key = "${project.getAppropiateGroupId().value}:${project.getAppropiateArtifactId().value}:${project.getAppropiateVersion().value}"
            key to project.pomLocation
        }
        modelResolver = MavenModelResolver(workspaceMap)

        return rootProjects.map { analyzeProject(it, allProjects, projectMap, false, true) }.sortedBy { it.artifactId }
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

                PomSurgicalEditor.edit(project.pomLocation) {
                    updateProjectVersion(newVersion)
                }

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
            val model = project.model

            PomSurgicalEditor.edit(project.pomLocation) {
                // Update parent
                if (model.parent != null) {
                    val key = Pair(model.parent.groupId, model.parent.artifactId)
                    if (versionMap.containsKey(key)) {
                        updateParentVersion(model.parent.groupId, model.parent.artifactId, versionMap[key]!!)
                    }
                }

                // Update dependencies
                model.dependencies?.forEach { dep ->
                    val key = Pair(dep.groupId, dep.artifactId)
                    if (versionMap.containsKey(key)) {
                        val newVersion = versionMap[key]!!
                        if (dep.version != null && dep.version.startsWith("\${")) {
                            val propName = dep.version.substring(2, dep.version.length - 1)
                            updateProperty(propName, newVersion)
                        } else if (dep.version != null) {
                            updateDependencyVersion(dep.groupId, dep.artifactId, newVersion)
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
                            updateProperty(propName, newVersion)
                        } else if (dep.version != null) {
                            updateDependencyVersion(dep.groupId, dep.artifactId, newVersion)
                        }
                    }
                }

                // Also check properties directly for pattern artifactId.version
                versionMap.forEach { (key, newVersion) ->
                    val artifactId = key.second
                    val directPropName = "$artifactId.version"
                    if (model.properties.containsKey(directPropName)) {
                        updateProperty(directPropName, newVersion)
                    }
                }
            }
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

    private fun isSpringBootProject(project: MavenProject, allProjects: List<MavenProject>, projectMap: Map<String, MavenProject>): Boolean {
        fun isSb(p: org.apache.maven.model.Parent?): Boolean {
            if (p == null) return false
            return p.groupId == "org.springframework.boot" || 
                   p.artifactId == "spring-boot-starter-parent" || 
                   p.artifactId == "spring-boot-dependencies"
        }

        if (isSb(project.model.parent)) return true
        
        // Check for BOM import
        val hasBom = project.model.dependencyManagement?.dependencies?.any { 
            it.groupId == "org.springframework.boot" && it.artifactId == "spring-boot-dependencies" && it.scope == "import" 
        } == true
        if (hasBom) return true
        
        return false
    }

    fun analyzeProject(project: MavenProject, allProjects: List<MavenProject>, projectMap: Map<String, MavenProject>, resolveProps: Boolean, isRoot: Boolean = false): ProjectAnalysis {
        val groupId = project.groupId()
        val artifactId = project.artifactId()
        val version = project.version()
        
        val usages = findUsages(project, allProjects)
        
        val hasSpringBootParent = isSpringBootProject(project, allProjects, projectMap)
        // Find Spring Boot version from the hierarchy
        val springBootVersion = findSpringBootVersion(project, allProjects, projectMap)

        val (managedProperties, error) = if (hasSpringBootParent && resolveProps) {
            resolveManagedPropertiesWithError(project, allProjects)
        } else {
            Pair(emptyList<ManagedProperty>(), null)
        }

        return ProjectAnalysis(
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            path = project.pomLocation.absolutePath,
            modules = project.modules.map { analyzeProject(it, allProjects, projectMap, resolveProps, false) }.sortedBy { it.artifactId },
            usages = usages,
            hasSpringBootParent = hasSpringBootParent,
            springBootVersion = springBootVersion,
            managedProperties = managedProperties,
            error = error,
            isRoot = isRoot
        )
    }

    private fun findSpringBootVersion(project: MavenProject, allProjects: List<MavenProject>, projectMap: Map<String, MavenProject>): String? {
        fun getSbVersion(p: org.apache.maven.model.Parent?): String? {
            if (p == null) return null
            if (p.groupId == "org.springframework.boot" || 
                p.artifactId == "spring-boot-starter-parent" || 
                p.artifactId == "spring-boot-dependencies") return p.version
            return null
        }

        getSbVersion(project.model.parent)?.let { return it }

        // Check for BOM import
        project.model.dependencyManagement?.dependencies?.find { 
            it.groupId == "org.springframework.boot" && it.artifactId == "spring-boot-dependencies" && it.scope == "import" 
        }?.let { return it.version }
        
        return null
    }

    private fun resolveManagedPropertiesWithError(project: MavenProject, allProjects: List<MavenProject>): Pair<List<ManagedProperty>, String?> {
        return try {
            Pair(resolveManagedProperties(project, allProjects), null)
        } catch (e: Exception) {
            val rawProps = project.model.properties
            val managedProperties = rawProps.stringPropertyNames()
                .map { name ->
                    ManagedProperty(
                        name = name,
                        value = rawProps.getProperty(name) ?: "",
                        inheritedValue = null,
                        source = "Local POM",
                        isOverridden = true,
                        comment = findCommentForProperty(project.pomLocation, name)
                    )
                }.sortedBy { it.name }
            Pair(managedProperties, e.message ?: e.toString())
        }
    }

    private fun resolveManagedProperties(project: MavenProject, allProjects: List<MavenProject>): List<ManagedProperty> {
        val result = modelResolver.resolveModelResult(project.pomLocation)
        val effectiveModel = result.effectiveModel
        val rawProps = project.model.properties
        
        val bomModels = mutableMapOf<String, Model>()
        val allImports = result.modelIds.flatMap { modelId ->
            result.getRawModel(modelId).dependencyManagement?.dependencies ?: emptyList()
        }.filter { it.scope == "import" && it.type == "pom" }
         .distinctBy { "${it.groupId}:${it.artifactId}:${it.version}" }

        for (imp in allImports) {
            val groupId = interpolate(imp.groupId, effectiveModel.properties)
            val artifactId = interpolate(imp.artifactId, effectiveModel.properties)
            val version = interpolate(imp.version, effectiveModel.properties)
            
            val key = "$groupId:$artifactId:$version"
            if (bomModels.containsKey(key)) continue
            
            try {
                bomModels[key] = modelResolver.resolveModel(groupId, artifactId, version)
            } catch (e: Exception) {
                // Skip BOMs that cannot be resolved
            }
        }

        val allPropsMap = mutableMapOf<String, ManagedProperty>()
        
        // 1. Process effective model properties (Local and Parents)
        effectiveModel.properties.stringPropertyNames().forEach { name ->
            if (name.endsWith(".version") || name.contains("version")) {
                val value = effectiveModel.properties.getProperty(name)
                val isInRaw = rawProps.containsKey(name)
                
                // Determine source and inherited value from model hierarchy
                var source = if (isInRaw) "Local POM" else "Inherited"
                var inheritedValue: String? = null
                
                // Traverse hierarchy from child to parent (index 0 is project, index 1 is first parent)
                for (i in 1 until result.modelIds.size) {
                    val modelId = result.modelIds[i]
                    val rawModel = result.getRawModel(modelId)
                    if (rawModel.properties.containsKey(name)) {
                        inheritedValue = rawModel.properties.getProperty(name)
                        if (!isInRaw) {
                            source = rawModel.artifactId ?: "Parent"
                            if (source.contains("spring-boot")) source = "Spring Boot"
                        }
                        break
                    }
                }
                
                // If not found in parent hierarchy, check BOMs
                if (inheritedValue == null) {
                    for (bomModel in bomModels.values) {
                        val v = bomModel.properties.getProperty(name)
                        if (v != null) {
                            inheritedValue = v
                            if (!isInRaw) {
                                source = bomModel.artifactId
                            }
                            break
                        }
                    }
                }

                // If it's a Spring Boot project and source is still "Inherited", it might be from the starter parent itself
                if (source == "Inherited" && !isInRaw) {
                    source = "Spring Boot"
                }

                allPropsMap[name] = ManagedProperty(
                    name = name,
                    value = value,
                    inheritedValue = inheritedValue,
                    source = source,
                    isOverridden = isInRaw,
                    comment = if (isInRaw) findCommentForProperty(project.pomLocation, name) else null
                )
            }
        }

        // 2. Add properties from raw POM that were missed (e.g. if effective model resolution was incomplete)
        rawProps.stringPropertyNames().forEach { name ->
            if (!allPropsMap.containsKey(name) && (name.endsWith(".version") || name.contains("version"))) {
                allPropsMap[name] = ManagedProperty(
                    name = name,
                    value = rawProps.getProperty(name) ?: "",
                    inheritedValue = null,
                    source = "Local POM",
                    isOverridden = true,
                    comment = findCommentForProperty(project.pomLocation, name)
                )
            }
        }

        // 3. Add properties from BOMs that weren't in effective model
        for (bomModel in bomModels.values) {
            bomModel.properties.stringPropertyNames().forEach { name ->
                if ((name.endsWith(".version") || name.contains("version")) && !allPropsMap.containsKey(name)) {
                    val value = bomModel.properties.getProperty(name)
                    allPropsMap[name] = ManagedProperty(
                        name = name,
                        value = value,
                        inheritedValue = value,
                        source = bomModel.artifactId,
                        isOverridden = false
                    )
                }
            }
        }

        return allPropsMap.values.sortedBy { it.name }
    }

    private fun interpolate(value: String?, properties: Properties): String {
        if (value == null) return ""
        var result: String = value
        val regex = Regex("\\\$\\{(.+?)\\}")
        var match = regex.find(result)
        while (match != null) {
            val propName = match.groupValues[1]
            val propValue = properties.getProperty(propName) ?: match.value
            result = result.replace(match.value, propValue)
            match = regex.find(result)
        }
        return result
    }

    private fun valueFromParent(propertyName: String, project: MavenProject, allProjects: List<MavenProject>): String? {
        val parent = project.model.parent ?: return null

        // 1. Try workspace first
        val workspaceParent = allProjects.find {
            val gid = it.getAppropiateGroupId().value
            val aid = it.getAppropiateArtifactId().value
            val v = it.getAppropiateVersion().value
            gid == parent.groupId && aid == parent.artifactId && v == parent.version
        }
        if (workspaceParent != null) {
            return try {
                val parentModel = modelResolver.resolveEffectiveModel(workspaceParent.pomLocation)
                parentModel.properties.getProperty(propertyName)
            } catch (e: Exception) {
                null
            }
        }

        // 2. Fallback to local repository
        return try {
            val localRepository = File(System.getProperty("user.home"), ".m2/repository")
            val parentPom = localRepository.resolve(parent.groupId.replace('.', File.separatorChar))
                .resolve(parent.artifactId)
                .resolve(parent.version)
                .resolve("${parent.artifactId}-${parent.version}.pom")
            
            if (parentPom.exists()) {
                val parentModel = modelResolver.resolveEffectiveModel(parentPom)
                parentModel.properties.getProperty(propertyName)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun findCommentForProperty(pomFile: File, propertyName: String): String? {
        return try {
            val content = pomFile.readText(StandardCharsets.UTF_8)
            val regex = Regex("<!--\\s*(.*?)\\s*-->\\s*<${Regex.escape(propertyName)}>")
            regex.find(content)?.groups?.get(1)?.value?.trim()
        } catch (e: Exception) {
            null
        }
    }

    fun upgradeSpringBoot(basePath: Path, maxDepth: Int, projectPath: String, newVersion: String) {
        val normalizedPath = Paths.get(projectPath).toAbsolutePath().normalize().toString()
        val rootProjects = ScanProjectUtil.searchAllMavenProjects(basePath.toFile(), maxDepth)
        val allProjects = flattenProjects(rootProjects)
        
        val project = allProjects.find { 
            Paths.get(it.pomLocation.absolutePath).toAbsolutePath().normalize().toString() == normalizedPath 
        } ?: throw RuntimeException("Project not found: $projectPath")

        val model = project.model
        if (model.parent != null && model.parent.artifactId.contains("spring-boot")) {
            PomSurgicalEditor.edit(project.pomLocation) {
                updateParentVersion(model.parent.groupId, model.parent.artifactId, newVersion)
            }
            // Run maven install in background to fetch new parent/dependencies
            mavenCommandService.runInstallInBackground(project.pomLocation)
        }
    }

    fun overrideProperty(
        basePath: Path,
        maxDepth: Int,
        projectPath: String,
        propertyName: String,
        newValue: String,
        remark: String?
    ) {
        val normalizedPath = Paths.get(projectPath).toAbsolutePath().normalize().toString()
        val rootProjects = ScanProjectUtil.searchAllMavenProjects(basePath.toFile(), maxDepth)
        val allProjects = flattenProjects(rootProjects)

        val project = allProjects.find {
            Paths.get(it.pomLocation.absolutePath).toAbsolutePath().normalize().toString() == normalizedPath
        } ?: throw RuntimeException("Project not found: $projectPath")

        PomSurgicalEditor.edit(project.pomLocation) {
            upsertProperty(propertyName, newValue, remark)
        }
        
        // Run maven install in background to fetch any new BOMs or dependencies
        mavenCommandService.runInstallInBackground(project.pomLocation)
    }

    fun removePropertyOverride(
        basePath: Path,
        maxDepth: Int,
        projectPath: String,
        propertyName: String
    ) {
        val normalizedPath = Paths.get(projectPath).toAbsolutePath().normalize().toString()
        val rootProjects = ScanProjectUtil.searchAllMavenProjects(basePath.toFile(), maxDepth)
        val allProjects = flattenProjects(rootProjects)

        val project = allProjects.find {
            Paths.get(it.pomLocation.absolutePath).toAbsolutePath().normalize().toString() == normalizedPath
        } ?: throw RuntimeException("Project not found: $projectPath")

        PomSurgicalEditor.edit(project.pomLocation) {
            removeProperty(propertyName)
        }
        
        // Run maven install in background
        mavenCommandService.runInstallInBackground(project.pomLocation)
    }

    fun getManagedProperties(basePath: Path, maxDepth: Int, projectPath: String): List<ManagedProperty> {
        val rootProjects = ScanProjectUtil.searchAllMavenProjects(basePath.toFile(), maxDepth)
        val allProjects = flattenProjects(rootProjects)
        val normalizedPath = Paths.get(projectPath).toAbsolutePath().normalize().toString()
        val project = allProjects.find { 
            Paths.get(it.pomLocation.absolutePath).toAbsolutePath().normalize().toString() == normalizedPath 
        } ?: throw RuntimeException("Project not found: $projectPath")

        // Update model resolver with all found projects
        val workspaceMap = allProjects.associate { p ->
            val key = "${p.getAppropiateGroupId().value}:${p.getAppropiateArtifactId().value}:${p.getAppropiateVersion().value}"
            key to p.pomLocation
        }
        modelResolver = MavenModelResolver(workspaceMap)

        val (props, error) = resolveManagedPropertiesWithError(project, allProjects)
        return props
    }

    fun getBuildOrder(basePath: Path, maxDepth: Int): List<ProjectAnalysis> {
        val rootProjects = scanAndAnalyze(basePath, maxDepth)
        val allProjects = rootProjects.flatMap { flattenAnalysis(it) }
        val projectByKey = allProjects.associateBy { "${it.groupId}:${it.artifactId}" }
        val dependencies = mutableMapOf<String, MutableSet<String>>() // Dependent -> Dependencies

        allProjects.forEach { project ->
            val projectKey = "${project.groupId}:${project.artifactId}"
            
            // Dependencies from usages
            project.usages.forEach { usage ->
                val dependentProject = allProjects.find { 
                    Paths.get(it.path).toAbsolutePath().normalize().toString() == 
                    Paths.get(usage.path).toAbsolutePath().normalize().toString() 
                }
                if (dependentProject != null && dependentProject != project) {
                    val depKey = "${dependentProject.groupId}:${dependentProject.artifactId}"
                    dependencies.getOrPut(depKey) { mutableSetOf() }.add(projectKey)
                }
            }
            
            // Parent-child dependency for build order
            project.modules.forEach { module ->
                val moduleKey = "${module.groupId}:${module.artifactId}"
                dependencies.getOrPut(moduleKey) { mutableSetOf() }.add(projectKey)
            }
        }

        val sortedKeys = topologicalSort(projectByKey.keys, dependencies)
        return sortedKeys.mapNotNull { projectByKey[it] }
    }

    fun exportToExcel(projects: List<ProjectAnalysis>): ByteArray {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Build Order")

        val header = sheet.createRow(0)
        header.createCell(0).setCellValue("Build Order")
        header.createCell(1).setCellValue("Project Name")
        header.createCell(2).setCellValue("Group ID")
        header.createCell(3).setCellValue("Artifact ID")
        header.createCell(4).setCellValue("Current Version")

        projects.filter { it.isRoot }.forEachIndexed { index, project ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue((index + 1).toDouble())
            row.createCell(1).setCellValue(project.artifactId)
            row.createCell(2).setCellValue(project.groupId)
            row.createCell(3).setCellValue(project.artifactId)
            row.createCell(4).setCellValue(project.version)
        }

        // Auto size columns
        for (i in 0..4) {
            sheet.autoSizeColumn(i)
        }

        val out = ByteArrayOutputStream()
        workbook.write(out)
        workbook.close()
        return out.toByteArray()
    }

    private fun flattenAnalysis(project: ProjectAnalysis): List<ProjectAnalysis> {
        return listOf(project) + project.modules.flatMap { flattenAnalysis(it) }
    }

    private fun findRootAnalysisByPath(roots: List<ProjectAnalysis>, path: String): ProjectAnalysis? {
        val normalizedPath = Paths.get(path).toAbsolutePath().normalize().toString()
        return roots.find { root ->
            flattenAnalysis(root).any { 
                Paths.get(it.path).toAbsolutePath().normalize().toString() == normalizedPath 
            }
        }
    }

    private fun topologicalSort(nodes: Set<String>, dependencies: Map<String, Set<String>>): List<String> {
        val result = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        val tempVisited = mutableSetOf<String>()

        fun visit(node: String) {
            if (visited.contains(node)) return
            if (tempVisited.contains(node)) return // Cycle, skip

            tempVisited.add(node)
            dependencies[node]?.forEach { visit(it) }
            tempVisited.remove(node)
            visited.add(node)
            result.add(node)
        }

        nodes.sorted().forEach { visit(it) } // Sort nodes for deterministic output
        return result
    }

    private fun findUsages(targetProject: MavenProject, allProjects: List<MavenProject>): List<ProjectUsage> {
        val groupId = targetProject.groupId()
        val artifactId = targetProject.artifactId()
        val targetRootPath = targetProject.rootPomPath()

        val usages = mutableListOf<ProjectUsage>()
        for (proj in allProjects) {
            // Skip if it's the same project
            if (proj.pomLocation.absolutePath == targetProject.pomLocation.absolutePath) continue

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
    val hasSpringBootParent: Boolean = false,
    val springBootVersion: String? = null,
    val managedProperties: List<ManagedProperty> = emptyList(),
    val error: String? = null,
    val isRoot: Boolean = false
)

data class ProjectUsage(
    val usedInGroupId: String,
    val usedInArtifactId: String,
    val usedVersion: String,
    val path: String
)
