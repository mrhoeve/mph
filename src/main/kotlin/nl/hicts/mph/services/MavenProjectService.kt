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

data class BulkVersionUpdate(
    val rootProjectPaths: List<String>,
    val prefix: String,
    val updateDependents: Boolean,
    val mode: String = "ADD_PREFIX",
    val branchName: String? = null,
    val updateProjects: Boolean = true
)

@Service
class MavenProjectService(
    private val mavenCommandService: MavenCommandService,
    private val gitService: GitService,
    private val nexusIqService: NexusIqService,
    private val settingsService: SettingsService,
    private val sbomService: SbomService
) {
    private companion object {
        const val SPRING_BOOT_GROUP = "org.springframework.boot"
        const val SPRING_BOOT_DEPENDENCIES = "spring-boot-dependencies"
        const val LOCAL_POM = "Local POM"
        const val VERSION_SUFFIX = ".version"
    }
    private val logger = LoggerFactory.getLogger(MavenProjectService::class.java)

    private var modelResolver = MavenModelResolver()

    fun getLatestTag(basePath: Path, maxDepth: Int, projectPath: String): TagInfo? {
        val project = findExposedProject(basePath, maxDepth, projectPath)
        return gitService.getLatestTagInfo(project.pomLocation)
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
        update: BulkVersionUpdate
    ) {
        gitService.clearCache()
        val rootProjects = ScanProjectUtil.searchAllMavenProjects(basePath.toFile(), maxDepth)
        val allProjects = flattenProjects(rootProjects)
        prepareBranches(allProjects, update.rootProjectPaths, update.branchName)
        if (update.prefix.isBlank() && !update.branchName.isNullOrBlank()) {
            logger.info("Prefix is blank and branch name is given. Skipping version update.")
            return
        }
        val versionMap = mutableMapOf<Pair<String, String>, String>()
        for (rootPath in update.rootProjectPaths) {
            val normalizedRootPath = normalizePath(rootPath)
            val rootProject = allProjects.find { 
                normalizePath(it.pomLocation.absolutePath) == normalizedRootPath
            } ?: continue
            val projectsToUpdate = flattenProjects(listOf(rootProject))
            for (project in projectsToUpdate) {
                val oldVersion = project.version()
                val newVersion = when (update.mode) {
                    "REMOVE_PREFIX" -> oldVersion.removePrefix(update.prefix)
                    "MANUAL" -> update.prefix
                    "CURRENT" -> oldVersion
                    else -> update.prefix + oldVersion
                }
                if (update.updateProjects) PomSurgicalEditor.edit(project.pomLocation) { updateProjectVersion(newVersion) }
                versionMap[project.groupId() to project.artifactId()] = newVersion
            }
        }
        if (update.updateDependents && versionMap.isNotEmpty()) updateProjectsDependents(allProjects, versionMap)
    }

    private fun prepareBranches(projects: List<MavenProject>, paths: List<String>, branchName: String?) {
        if (branchName.isNullOrBlank()) return
        paths.forEach { path ->
            try {
                val project = projects.find { it.pomLocation.absolutePath == path }
                    ?: throw IllegalArgumentException("Project was not found in the configured workspace: $path")
                gitService.prepareBranch(project.pomLocation, branchName)
            } catch (e: Exception) {
                throw RuntimeException("Failed to prepare Git branch for $path: ${e.message}")
            }
        }
    }

    private fun normalizePath(path: String): String = Paths.get(path).toAbsolutePath().normalize().toString()

    private fun updateProjectsDependents(allProjects: List<MavenProject>, versionMap: Map<Pair<String, String>, String>) {
        allProjects.forEach { project ->
            val model = project.model
            PomSurgicalEditor.edit(project.pomLocation) {
                model.parent?.let { parent ->
                    versionMap[parent.groupId to parent.artifactId]?.let { newVersion ->
                        updateParentVersion(parent.groupId, parent.artifactId, newVersion)
                    }
                }
                model.dependencies.orEmpty().forEach { updateDependencyReference(it, versionMap) }
                model.dependencyManagement?.dependencies.orEmpty().forEach { updateDependencyReference(it, versionMap) }
                versionMap.forEach { (key, newVersion) ->
                    val directPropName = key.second + VERSION_SUFFIX
                    if (model.properties.containsKey(directPropName)) updateProperty(directPropName, newVersion)
                }
            }
        }
    }

    private fun PomSurgicalEditor.Session.updateDependencyReference(
        dependency: org.apache.maven.model.Dependency,
        versionMap: Map<Pair<String, String>, String>
    ) {
        val newVersion = versionMap[dependency.groupId to dependency.artifactId] ?: return
        val currentVersion = dependency.version ?: return
        if (!currentVersion.startsWith("\${")) {
            updateDependencyVersion(dependency.groupId, dependency.artifactId, newVersion)
            return
        }
        val propertyName = currentVersion.substring(2, currentVersion.length - 1)
        if (!isMavenInternalProperty(propertyName)) updateProperty(propertyName, newVersion)
    }

    fun syncDevelop(basePath: Path, maxDepth: Int, rootProjectPaths: List<String>, mergeDevelop: Boolean = false): List<String> {
        val projectsByPath = flattenProjects(ScanProjectUtil.searchAllMavenProjects(basePath.toFile(), maxDepth))
            .associateBy { it.pomLocation.absolutePath }
        val messages = mutableListOf<String>()
        for (rootPath in rootProjectPaths) {
            try {
                val project = projectsByPath[rootPath]
                    ?: throw IllegalArgumentException("Project was not found in the configured workspace: $rootPath")
                gitService.syncDevelop(project.pomLocation, mergeDevelop)?.let {
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

    private fun findExposedProject(basePath: Path, maxDepth: Int, projectPath: String): MavenProject {
        return flattenProjects(ScanProjectUtil.searchAllMavenProjects(basePath.toFile(), maxDepth))
            .find { it.pomLocation.absolutePath == projectPath }
            ?: throw IllegalArgumentException("Project was not found in the configured workspace: $projectPath")
    }

    private fun isSpringBootProject(project: MavenProject): Boolean {
        fun isSb(p: org.apache.maven.model.Parent?): Boolean {
            if (p == null) return false
            return p.groupId == SPRING_BOOT_GROUP ||
                   p.artifactId == "spring-boot-starter-parent" || 
                   p.artifactId == SPRING_BOOT_DEPENDENCIES
        }

        if (isSb(project.model.parent)) return true
        
        // Check for BOM import
        val hasBom = project.model.dependencyManagement?.dependencies?.any { 
            it.groupId == SPRING_BOOT_GROUP && it.artifactId == SPRING_BOOT_DEPENDENCIES && it.scope == "import"
        } == true
        if (hasBom) return true
        
        return false
    }

    fun analyzeProject(project: MavenProject, allProjects: List<MavenProject>, projectMap: Map<String, MavenProject>, resolveProps: Boolean, isRoot: Boolean = false, usageMap: Map<Pair<String, String>, List<ProjectUsage>> = emptyMap()): ProjectAnalysis {
        val groupId = project.groupId()
        val artifactId = project.artifactId()
        val version = project.version()
        
        val usages = usageMap[Pair(groupId, artifactId)]?.filter { it.path != project.pomLocation.absolutePath } ?: emptyList()
        
        val hasSpringBootParent = isSpringBootProject(project)
        val springBootVersion = findSpringBootVersion(project)

        var managedProperties = emptyList<ManagedProperty>()
        var error: String? = null
        var nexusIqResult: NexusIqResult? = null

        val settings = settingsService.loadSettings()
        val applicationId = nexusIqService.extractNexusIqAppId(project.pomLocation.parent, settings)
        val canScanNexusIq = applicationId != null

        if (resolveProps) {
            try {
                val result = modelResolver.resolveModelResult(project.pomLocation)
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
                    val violations = getProjectVulnerabilitiesFromModel(result.effectiveModel)
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
                            source = LOCAL_POM,
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
                
                (vVersion == prop.value) && (prop.name.contains(vArtifactId ?: "___") || (vArtifactId?.contains(prop.name.replace(VERSION_SUFFIX, "")) == true))
            } ?: emptyList()
            if (violationsForProp.isNotEmpty()) prop.copy(nexusIqViolations = violationsForProp) else prop
        }

        val gitStatus = if (isRoot) gitService.getGitStatus(project.pomLocation) else null

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

    private fun findSpringBootVersion(project: MavenProject): String? {
        fun getSbVersion(p: org.apache.maven.model.Parent?): String? {
            if (p == null) return null
            if (p.groupId == SPRING_BOOT_GROUP ||
                p.artifactId == "spring-boot-starter-parent" || 
                p.artifactId == SPRING_BOOT_DEPENDENCIES) return p.version
            return null
        }

        getSbVersion(project.model.parent)?.let { return it }

        // Check for BOM import
        project.model.dependencyManagement?.dependencies?.find { 
            it.groupId == SPRING_BOOT_GROUP && it.artifactId == SPRING_BOOT_DEPENDENCIES && it.scope == "import"
        }?.let { return it.version }
        
        return null
    }

    private fun resolveManagedPropertiesFromResult(project: MavenProject, result: ModelBuildingResult): List<ManagedProperty> {
        val effectiveModel = result.effectiveModel
        val rawProps = project.model.properties
        val bomModels = resolveImportedBoms(result, effectiveModel.properties)
        val properties = effectiveVersionProperties(project, result, bomModels).toMutableMap()
        addMissingRawProperties(project, properties)
        addMissingBomProperties(bomModels, properties)
        return properties.values.sortedBy { it.name }
    }

    private data class PendingBom(val dependency: org.apache.maven.model.Dependency, val properties: Properties)
    private data class PropertyOrigin(val source: String, val inheritedValue: String?)

    private fun resolveImportedBoms(result: ModelBuildingResult, properties: Properties): Map<String, Model> {
        val pending = ArrayDeque<PendingBom>()
        importedDependencies(result).forEach { pending.add(PendingBom(it, properties)) }
        val models = mutableMapOf<String, Model>()
        while (pending.isNotEmpty()) {
            val item = pending.removeFirst()
            val groupId = interpolate(item.dependency.groupId, item.properties)
            val artifactId = interpolate(item.dependency.artifactId, item.properties)
            val version = interpolate(item.dependency.version, item.properties)
            val key = "$groupId:$artifactId:$version"
            if (key in models) continue
            try {
                val bomResult = modelResolver.resolveModelResult(groupId, artifactId, version)
                val model = bomResult.effectiveModel
                models[key] = model
                importedDependencies(bomResult).forEach { pending.add(PendingBom(it, model.properties)) }
            } catch (e: Exception) {
                logger.debug("Could not resolve imported BOM $key", e)
            }
        }
        return models
    }

    private fun importedDependencies(result: ModelBuildingResult): List<org.apache.maven.model.Dependency> =
        result.modelIds.flatMap { modelId ->
            result.getRawModel(modelId).dependencyManagement?.dependencies.orEmpty()
        }.filter { it.scope == "import" && it.type == "pom" }
            .distinctBy { "${it.groupId}:${it.artifactId}:${it.version}" }

    private fun effectiveVersionProperties(
        project: MavenProject,
        result: ModelBuildingResult,
        bomModels: Map<String, Model>
    ): Map<String, ManagedProperty> {
        val rawProps = project.model.properties
        return result.effectiveModel.properties.stringPropertyNames()
            .filter(::isVersionProperty)
            .associateWith { name ->
                val isLocal = rawProps.containsKey(name)
                val origin = findPropertyOrigin(name, isLocal, result, bomModels.values)
                ManagedProperty(
                    name = name,
                    value = result.effectiveModel.properties.getProperty(name),
                    inheritedValue = origin.inheritedValue,
                    source = origin.source,
                    isOverridden = isLocal,
                    comment = if (isLocal) findCommentForProperty(project.pomLocation, name) else null
                )
            }
    }

    private fun findPropertyOrigin(
        name: String,
        isLocal: Boolean,
        result: ModelBuildingResult,
        bomModels: Collection<Model>
    ): PropertyOrigin {
        val parent = result.modelIds.drop(1).map(result::getRawModel)
            .firstOrNull { it.properties.containsKey(name) }
        if (parent != null) {
            val inheritedValue = parent.properties.getProperty(name)
            if (isLocal) return PropertyOrigin(LOCAL_POM, inheritedValue)
            val source = parent.artifactId?.let { if (it.contains("spring-boot")) "Spring Boot" else it } ?: "Parent"
            return PropertyOrigin(source, inheritedValue)
        }
        val bom = bomModels.firstOrNull { it.properties.containsKey(name) }
        return when {
            bom == null -> PropertyOrigin(if (isLocal) LOCAL_POM else "Inherited", null)
            isLocal -> PropertyOrigin(LOCAL_POM, bom.properties.getProperty(name))
            else -> PropertyOrigin(bom.artifactId, bom.properties.getProperty(name))
        }
    }

    private fun addMissingRawProperties(project: MavenProject, properties: MutableMap<String, ManagedProperty>) {
        project.model.properties.stringPropertyNames()
            .filter(::isVersionProperty)
            .filterNot(properties::containsKey)
            .forEach { name ->
                properties[name] = ManagedProperty(
                    name, project.model.properties.getProperty(name) ?: "", null, LOCAL_POM, true,
                    findCommentForProperty(project.pomLocation, name)
                )
            }
    }

    private fun addMissingBomProperties(
        bomModels: Map<String, Model>,
        properties: MutableMap<String, ManagedProperty>
    ) {
        bomModels.values.forEach { model ->
            model.properties.stringPropertyNames()
                .filter(::isVersionProperty)
                .filterNot(properties::containsKey)
                .forEach { name ->
                    val value = model.properties.getProperty(name)
                    properties[name] = ManagedProperty(name, value, value, model.artifactId, false)
                }
        }
    }

    private fun isVersionProperty(name: String): Boolean = name.endsWith(VERSION_SUFFIX) || name.contains("version")

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
                        source = LOCAL_POM,
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

        val violations = projectModelResult
            ?.effectiveModel
            ?.let(::getProjectVulnerabilitiesFromModel)
            ?: emptyList()

        if (violations.isEmpty()) {
            return managedProperties
        }

        return managedProperties.map { prop ->
            val violationsForProp = violations.filter { violation ->
                val parts = violation.componentIdentifier.split(":")
                val vArtifactId = parts.getOrNull(2)
                val vVersion = parts.getOrNull(3)
                
                (vVersion == prop.value) && (prop.name.contains(vArtifactId ?: "___") || (vArtifactId?.contains(prop.name.replace(VERSION_SUFFIX, "")) == true))
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

    private fun topologicalSortIntoStages(nodes: Set<String>, dependencies: Map<String, Set<String>>): List<List<String>> {
        val (inDegree, adjacency) = buildDependencyGraph(nodes, dependencies)
        val result = mutableListOf<List<String>>()
        var currentStage = nodes.filter { (inDegree[it] ?: 0) == 0 }
        while (currentStage.isNotEmpty()) {
            result.add(currentStage)
            currentStage = nextBuildStage(currentStage, adjacency, inDegree)
        }
        val processedNodes = result.flatten().toSet()
        val remaining = (nodes - processedNodes).sorted()
        if (remaining.isNotEmpty()) result.add(remaining)
        return result
    }

    private fun buildDependencyGraph(
        nodes: Set<String>,
        dependencies: Map<String, Set<String>>
    ): Pair<MutableMap<String, Int>, Map<String, Set<String>>> {
        val inDegree = nodes.associateWith { 0 }.toMutableMap()
        val adjacency = mutableMapOf<String, MutableSet<String>>()
        dependencies.forEach { (dependent, required) ->
            if (dependent !in nodes) return@forEach
            required.filter(nodes::contains).forEach { dependency ->
                if (adjacency.getOrPut(dependency) { mutableSetOf() }.add(dependent)) {
                    inDegree[dependent] = inDegree.getValue(dependent) + 1
                }
            }
        }
        return inDegree to adjacency
    }

    private fun nextBuildStage(
        current: List<String>,
        adjacency: Map<String, Set<String>>,
        inDegree: MutableMap<String, Int>
    ): List<String> = current.flatMap { adjacency[it].orEmpty() }.mapNotNull { dependent ->
        val degree = inDegree.getValue(dependent) - 1
        inDegree[dependent] = degree
        dependent.takeIf { degree == 0 }
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
