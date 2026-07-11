package nl.hicts.mph.services

import nl.hicts.mph.models.*
import org.apache.maven.model.Model
import org.apache.maven.model.building.ModelBuildingResult
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.slf4j.LoggerFactory
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
    val comment: String? = null,
    val nexusIqViolations: List<NexusIqPolicyViolation> = emptyList()
)

data class TagInfo(
    val version: String,
    val tagName: String
)

data class GitStatus(
    val branchName: String,
    val aheadCount: Int,
    val behindCount: Int
)

@Service
class MavenProjectService(
    private val mavenCommandService: MavenCommandService,
    private val gitService: GitService,
    private val nexusIqService: NexusIqService,
    private val settingsService: SettingsService,
    private val sbomService: SbomService
) {
    private val logger = LoggerFactory.getLogger(MavenProjectService::class.java)

    private var modelResolver = MavenModelResolver()

    fun getLatestTag(projectPath: String): TagInfo? {
        return gitService.getLatestTagInfo(projectPath)
    }

    fun scanAndAnalyze(basePath: Path, maxDepth: Int): List<ProjectAnalysis> {
        gitService.clearCache()
        val rootProjects = ScanProjectUtil.searchAllMavenProjects(basePath.toFile(), maxDepth)
        val allProjects = flattenProjects(rootProjects)
        
        val projectMap = allProjects.associateBy { 
            "${it.getAppropiateGroupId().value}:${it.getAppropiateArtifactId().value}:${it.getAppropiateVersion().value}" 
        }

        // Pre-calculate usages for all projects to avoid O(N^2) complexity
        val usageMap = buildUsageMap(allProjects)

        // Update model resolver with all found projects to handle multi-module dependencies
        val workspaceMap = allProjects.associate { project ->
            val key = "${project.getAppropiateGroupId().value}:${project.getAppropiateArtifactId().value}:${project.getAppropiateVersion().value}"
            key to project.pomLocation
        }
        modelResolver = MavenModelResolver(workspaceMap)
        sbomService.setWorkspace(workspaceMap)

        return rootProjects.map { analyzeProject(it, allProjects, projectMap, false, true, usageMap) }.sortedBy { it.artifactId }
    }

    fun updateVersions(basePath: Path, maxDepth: Int, groupId: String, artifactId: String, newVersion: String) {
        gitService.clearCache()
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
        mode: String = "ADD_PREFIX",
        branchName: String? = null,
        updateProjects: Boolean = true
    ) {
        gitService.clearCache()
        val rootProjects = ScanProjectUtil.searchAllMavenProjects(basePath.toFile(), maxDepth)
        val allProjects = flattenProjects(rootProjects)

        // Prepare branch if requested
        if (!branchName.isNullOrBlank()) {
            for (rootPath in rootProjectPaths) {
                try {
                    gitService.prepareBranch(rootPath, branchName)
                } catch (e: Exception) {
                    throw RuntimeException("Failed to prepare Git branch for $rootPath: ${e.message}")
                }
            }
        }

        // If no prefix but branch name given, we just wanted to create branches
        if (prefix.isBlank() && !branchName.isNullOrBlank()) {
            logger.info("Prefix is blank and branch name is given. Skipping version update.")
            return
        }

        val versionMap = mutableMapOf<Pair<String, String>, String>()
        val allProjectsToUpdate = mutableListOf<MavenProject>()

        // 1. Update the selected projects and their modules
        for (rootPath in rootProjectPaths) {
            val normalizedRootPath = Paths.get(rootPath).toAbsolutePath().normalize().toString()
            val rootProject = allProjects.find { 
                Paths.get(it.pomLocation.absolutePath).toAbsolutePath().normalize().toString() == normalizedRootPath 
            } ?: continue
            val projectsToUpdate = flattenProjects(listOf(rootProject))
            allProjectsToUpdate.addAll(projectsToUpdate)

            for (project in projectsToUpdate) {
                val oldVersion = project.version()
                val newVersion = when (mode) {
                    "REMOVE_PREFIX" -> {
                        if (oldVersion.startsWith(prefix)) {
                            oldVersion.substring(prefix.length)
                        } else {
                            oldVersion
                        }
                    }
                    "MANUAL" -> prefix
                    "CURRENT" -> oldVersion
                    else -> prefix + oldVersion
                }
                
                val groupId = project.groupId()
                val artifactId = project.artifactId()

                if (updateProjects) {
                    PomSurgicalEditor.edit(project.pomLocation) {
                        updateProjectVersion(newVersion)
                    }
                }

                versionMap[Pair(groupId, artifactId)] = newVersion
            }
        }

        // 2. Update dependents if requested
        if (updateDependents && versionMap.isNotEmpty()) {
            // Fix: even if updateProjects is false, we should still update usages in all projects
            // including those that were "updated" (e.g. to update modules to use the correct parent version)
            val projectsToUpdateUsagesIn = allProjects
            updateProjectsDependents(projectsToUpdateUsagesIn, versionMap)
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
                            if (!isMavenInternalProperty(propName)) {
                                updateProperty(propName, newVersion)
                            }
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
                            if (!isMavenInternalProperty(propName)) {
                                updateProperty(propName, newVersion)
                            }
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

    fun syncDevelop(rootProjectPaths: List<String>, mergeDevelop: Boolean = false): List<String> {
        val messages = mutableListOf<String>()
        for (rootPath in rootProjectPaths) {
            try {
                gitService.syncDevelop(rootPath, mergeDevelop)?.let {
                    messages.add(it)
                }
            } catch (e: Exception) {
                messages.add("Failed to sync develop for $rootPath: ${e.message}")
            }
        }
        return messages
    }

    private fun isMavenInternalProperty(propName: String): Boolean {
        val internalProps = setOf(
            "project.version", "version",
            "project.groupId", "groupId",
            "project.artifactId", "artifactId",
            "project.parent.version", "parent.version",
            "project.parent.groupId", "parent.groupId",
            "project.parent.artifactId", "parent.artifactId",
            "project.basedir", "basedir",
            "project.build.directory", "build.directory"
        )
        return internalProps.contains(propName)
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

    fun analyzeProject(project: MavenProject, allProjects: List<MavenProject>, projectMap: Map<String, MavenProject>, resolveProps: Boolean, isRoot: Boolean = false, usageMap: Map<Pair<String, String>, List<ProjectUsage>> = emptyMap()): ProjectAnalysis {
        val groupId = project.groupId()
        val artifactId = project.artifactId()
        val version = project.version()
        
        val usages = usageMap[Pair(groupId, artifactId)]?.filter { it.path != project.pomLocation.absolutePath } ?: emptyList()
        
        val hasSpringBootParent = isSpringBootProject(project, allProjects, projectMap)
        val springBootVersion = findSpringBootVersion(project, allProjects, projectMap)

        var managedProperties = emptyList<ManagedProperty>()
        var error: String? = null
        var nexusIqResult: NexusIqResult? = null
        var effectiveModel: Model? = null

        val settings = settingsService.loadSettings()
        val applicationId = nexusIqService.extractNexusIqAppId(project.pomLocation.parent, settings)
        val canScanNexusIq = applicationId != null

        if (resolveProps) {
            try {
                val result = modelResolver.resolveModelResult(project.pomLocation)
                effectiveModel = result.effectiveModel
                managedProperties = resolveManagedPropertiesFromResult(project, result)
                
                if (settings.nexusIqUrl.isNullOrBlank()) {
                    nexusIqResult = NexusIqResult(
                        applicationPublicId = applicationId ?: artifactId,
                        message = "Nexus IQ not configured"
                    )
                } else if (applicationId == null) {
                    nexusIqResult = NexusIqResult(
                        applicationPublicId = artifactId,
                        message = "Nexus IQ scan skipped: No Jenkinsfile found"
                    )
                } else {
                    val violations = getProjectVulnerabilitiesFromModel(effectiveModel)
                    nexusIqResult = NexusIqResult(
                        applicationPublicId = applicationId,
                        policyViolations = violations,
                        reportHtmlUrl = nexusIqService.getReportUrl(applicationId, settings)
                    )
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                // Fallback to raw properties if effective resolution fails
                val rawProps = project.model.properties
                managedProperties = rawProps.stringPropertyNames()
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
            }
        }

        // Map violations back to properties
        val propertiesWithViolations = managedProperties.map { prop ->
            val violationsForProp = nexusIqResult?.policyViolations?.filter { violation ->
                val parts = violation.componentIdentifier.split(":")
                val vArtifactId = parts.getOrNull(2)
                val vVersion = parts.getOrNull(3)
                
                (vVersion == prop.value) && (prop.name.contains(vArtifactId ?: "___") || (vArtifactId?.contains(prop.name.replace(".version", "")) == true))
            } ?: emptyList()
            if (violationsForProp.isNotEmpty()) prop.copy(nexusIqViolations = violationsForProp) else prop
        }

        val gitStatus = if (isRoot) gitService.getGitStatus(project.pomLocation.absolutePath) else null

        return ProjectAnalysis(
            groupId = groupId,
            artifactId = artifactId,
            version = version,
            path = project.pomLocation.absolutePath,
            modules = project.modules.map { analyzeProject(it, allProjects, projectMap, resolveProps, false, usageMap) }.sortedBy { it.artifactId },
            usages = usages,
            hasSpringBootParent = hasSpringBootParent,
            canManageComponentVersions = hasSpringBootParent || !isRoot,
            springBootVersion = springBootVersion,
            managedProperties = propertiesWithViolations,
            latestTag = null,
            latestTagInfo = null,
            gitStatus = gitStatus,
            error = error,
            isRoot = isRoot,
            nexusIqResult = nexusIqResult,
            canScanNexusIq = canScanNexusIq
        )
    }

    private fun getProjectVulnerabilitiesFromModel(model: Model): List<NexusIqPolicyViolation> {
        val components = mutableListOf<Triple<String, String, String>>()
        model.dependencies?.forEach { dep ->
            components.add(Triple(dep.groupId, dep.artifactId, dep.version))
        }
        model.dependencyManagement?.dependencies?.forEach { dep ->
            components.add(Triple(dep.groupId, dep.artifactId, dep.version))
        }
        return nexusIqService.getVulnerabilitiesBatch(components).distinctBy { it.componentIdentifier }
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

    private fun resolveManagedPropertiesFromResult(project: MavenProject, result: ModelBuildingResult): List<ManagedProperty> {
        val effectiveModel = result.effectiveModel
        val rawProps = project.model.properties
        
        val bomModels = mutableMapOf<String, Model>()
        val allImports = result.modelIds.flatMap { modelId ->
            result.getRawModel(modelId).dependencyManagement?.dependencies ?: emptyList()
        }.filter { it.scope == "import" && it.type == "pom" }
         .distinctBy { "${it.groupId}:${it.artifactId}:${it.version}" }

        fun collectBom(groupId: String, artifactId: String, version: String) {
            val key = "$groupId:$artifactId:$version"
            if (bomModels.containsKey(key)) return
            
            try {
                val bomResult = modelResolver.resolveModelResult(groupId, artifactId, version)
                val bomModel = bomResult.effectiveModel
                bomModels[key] = bomModel

                bomResult.modelIds
                    .flatMap { modelId ->
                        bomResult.getRawModel(modelId).dependencyManagement?.dependencies ?: emptyList()
                    }
                    .filter { it.scope == "import" && it.type == "pom" }
                    .forEach { nestedImport ->
                        collectBom(
                            interpolate(nestedImport.groupId, bomModel.properties),
                            interpolate(nestedImport.artifactId, bomModel.properties),
                            interpolate(nestedImport.version, bomModel.properties)
                        )
                    }
            } catch (e: Exception) {
                // Skip BOMs that cannot be resolved
            }
        }

        for (imp in allImports) {
            collectBom(
                interpolate(imp.groupId, effectiveModel.properties),
                interpolate(imp.artifactId, effectiveModel.properties),
                interpolate(imp.version, effectiveModel.properties)
            )
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
        gitService.clearCache()
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
        gitService.clearCache()
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
        sbomService.setWorkspace(workspaceMap)

        var projectModelResult: ModelBuildingResult? = null
        val managedProperties = try {
            projectModelResult = modelResolver.resolveModelResult(project.pomLocation)
            resolveManagedPropertiesFromResult(project, projectModelResult)
        } catch (e: Exception) {
            val rawProps = project.model.properties
            rawProps.stringPropertyNames()
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
        }

        val settings = settingsService.loadSettings()
        val applicationId = nexusIqService.extractNexusIqAppId(project.pomLocation.parent, settings)

        if (settings.nexusIqUrl.isNullOrBlank() || applicationId == null) {
            return managedProperties
        }

        val violations = if (projectModelResult != null) getProjectVulnerabilitiesFromModel(projectModelResult!!.effectiveModel) else emptyList()

        if (violations.isEmpty()) {
            return managedProperties
        }

        return managedProperties.map { prop ->
            val violationsForProp = violations.filter { violation ->
                val parts = violation.componentIdentifier.split(":")
                val vArtifactId = parts.getOrNull(2)
                val vVersion = parts.getOrNull(3)
                
                (vVersion == prop.value) && (prop.name.contains(vArtifactId ?: "___") || (vArtifactId?.contains(prop.name.replace(".version", "")) == true))
            }
            if (violationsForProp.isNotEmpty()) prop.copy(nexusIqViolations = violationsForProp) else prop
        }
    }

    fun getBuildOrder(basePath: Path, maxDepth: Int): List<ProjectAnalysis> {
        val rootProjects = scanAndAnalyze(basePath, maxDepth)
        val allProjects = rootProjects.flatMap { flattenAnalysis(it) }

        // Map each project's path to its root project
        val projectPathToRoot = mutableMapOf<String, ProjectAnalysis>()
        rootProjects.forEach { root ->
            flattenAnalysis(root).forEach { project ->
                val normalizedPath = Paths.get(project.path).toAbsolutePath().normalize().toString()
                projectPathToRoot[normalizedPath] = root
            }
        }

        val dependencies = mutableMapOf<String, MutableSet<String>>() // Dependent Root Path -> Dependency Root Path

        allProjects.forEach { project ->
            val projectPath = Paths.get(project.path).toAbsolutePath().normalize().toString()
            val rootOfProject = projectPathToRoot[projectPath] ?: return@forEach

            project.usages.forEach { usage ->
                val usagePath = Paths.get(usage.path).toAbsolutePath().normalize().toString()
                val rootOfDependent = projectPathToRoot[usagePath]

                if (rootOfDependent != null && rootOfDependent != rootOfProject) {
                    // rootOfDependent depends on rootOfProject
                    dependencies.getOrPut(rootOfDependent.path) { mutableSetOf() }.add(rootOfProject.path)
                }
            }
        }

        val rootPaths = rootProjects.map { it.path }.toSet()
        val sortedStages = topologicalSortIntoStages(rootPaths, dependencies)

        val rootByPath = rootProjects.associateBy { it.path }
        val result = mutableListOf<ProjectAnalysis>()
        
        sortedStages.forEachIndexed { stageIndex, stagePaths ->
            stagePaths.sorted().forEach { path ->
                rootByPath[path]?.let { root ->
                    val deps = dependencies[path] ?: emptySet()
                    val dependsOnArtifactIds = deps.mapNotNull { rootByPath[it]?.artifactId }.sorted()
                    
                    result.add(root.copy(
                        buildStep = stageIndex + 1,
                        dependsOn = dependsOnArtifactIds
                    ))
                }
            }
        }
        
        return result
    }

    fun exportToExcel(projects: List<ProjectAnalysis>): ByteArray {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Build Order")

        val header = sheet.createRow(0)
        header.createCell(0).setCellValue("Build Step")
        header.createCell(1).setCellValue("Project Name")
        header.createCell(2).setCellValue("Current Version")
        header.createCell(3).setCellValue("Depends On")

        projects.filter { it.isRoot }.forEachIndexed { index, project ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(project.buildStep.toDouble())
            row.createCell(1).setCellValue(project.artifactId)
            row.createCell(2).setCellValue(project.version)
            row.createCell(3).setCellValue(project.dependsOn.joinToString(", "))
        }

        // Auto size columns
        for (i in 0..3) {
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

    private fun topologicalSortIntoStages(nodes: Set<String>, dependencies: Map<String, Set<String>>): List<List<String>> {
        val inDegree = mutableMapOf<String, Int>()
        val adj = mutableMapOf<String, MutableSet<String>>() // node -> things that depend on it

        nodes.forEach { inDegree[it] = 0 }

        dependencies.forEach { (dependent, deps) ->
            deps.forEach { dep ->
                if (nodes.contains(dep) && nodes.contains(dependent)) {
                    if (adj.getOrPut(dep) { mutableSetOf() }.add(dependent)) {
                        inDegree[dependent] = (inDegree[dependent] ?: 0) + 1
                    }
                }
            }
        }

        val result = mutableListOf<List<String>>()
        var currentStage = nodes.filter { (inDegree[it] ?: 0) == 0 }

        while (currentStage.isNotEmpty()) {
            result.add(currentStage)
            val nextStage = mutableListOf<String>()
            currentStage.forEach { u ->
                adj[u]?.forEach { v ->
                    inDegree[v] = inDegree[v]!! - 1
                    if (inDegree[v] == 0) {
                        nextStage.add(v)
                    }
                }
            }
            currentStage = nextStage
        }

        // Handle cycles
        val processedNodes = result.flatten().toSet()
        if (processedNodes.size < nodes.size) {
            val remaining = (nodes - processedNodes).sorted()
            if (remaining.isNotEmpty()) {
                result.add(remaining)
            }
        }

        return result
    }

    private fun buildUsageMap(allProjects: List<MavenProject>): Map<Pair<String, String>, List<ProjectUsage>> {
        val result = mutableMapOf<Pair<String, String>, MutableList<ProjectUsage>>()
        for (proj in allProjects) {
            val model = proj.model
            val projGroupId = proj.groupId()
            val projArtifactId = proj.artifactId()
            val projPath = proj.pomLocation.absolutePath

            fun addUsage(groupId: String?, artifactId: String?, version: String?) {
                if (groupId == null || artifactId == null) return
                
                val resolvedVersion = if (version != null && version.startsWith("\${")) {
                    val propName = version.substring(2, version.length - 1)
                    model.properties.getProperty(propName) ?: version
                } else {
                    version ?: "managed"
                }
                result.getOrPut(Pair(groupId, artifactId)) { mutableListOf() }.add(
                    ProjectUsage(projGroupId, projArtifactId, resolvedVersion, projPath)
                )
            }

            model.parent?.let { addUsage(it.groupId, it.artifactId, it.version) }
            model.dependencies?.forEach { addUsage(it.groupId, it.artifactId, it.version) }
            model.dependencyManagement?.dependencies?.forEach { addUsage(it.groupId, it.artifactId, it.version) }
        }
        return result
    }
}

private fun MavenProject.groupId() = this.getAppropiateGroupId().value
private fun MavenProject.artifactId() = this.getAppropiateArtifactId().value
private fun MavenProject.version() = this.getAppropiateVersion().value

data class ProjectAnalysis(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val path: String,
    val modules: List<ProjectAnalysis>,
    val usages: List<ProjectUsage>,
    val hasSpringBootParent: Boolean = false,
    val canManageComponentVersions: Boolean = false,
    val springBootVersion: String? = null,
    val managedProperties: List<ManagedProperty> = emptyList(),
    var latestTag: String? = null,
    var latestTagInfo: TagInfo? = null,
    var gitStatus: GitStatus? = null,
    val error: String? = null,
    val isRoot: Boolean = false,
    val nexusIqResult: NexusIqResult? = null,
    val canScanNexusIq: Boolean = false,
    val buildStep: Int = 0,
    val dependsOn: List<String> = emptyList()
)

data class ProjectUsage(
    val usedInGroupId: String,
    val usedInArtifactId: String,
    val usedVersion: String,
    val path: String
)
