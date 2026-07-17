package nl.hicts.mph.services

import nl.hicts.mph.models.MavenProject
import org.apache.maven.model.Model
import org.cyclonedx.Version
import org.cyclonedx.generators.BomGeneratorFactory
import org.cyclonedx.model.Bom
import org.cyclonedx.model.Component
import org.cyclonedx.model.License
import org.cyclonedx.model.LicenseChoice
import org.cyclonedx.model.ExternalReference
import org.cyclonedx.model.Hash
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest

data class SbomComponent(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val scope: String?,
    val type: String?,
    val description: String? = null,
    val licenses: List<String> = emptyList(),
    val dependencies: List<SbomComponent> = emptyList()
)

data class SbomDetails(
    val components: List<SbomComponent>,
    val rawXml: String,
    val rawJson: String
)

@Service
class SbomService {
    private var modelResolver = MavenModelResolver()
    private var workspaceProjects: Map<String, File> = emptyMap()

    fun generateSbom(projectPath: String, format: String): String {
        val bom = buildBom(projectPath)
        return if (format.lowercase() == "xml") {
            BomGeneratorFactory.createXml(Version.VERSION_15, bom).toXmlString()
        } else {
            BomGeneratorFactory.createJson(Version.VERSION_15, bom).toJsonString()
        }
    }

    @Suppress("DEPRECATION")
    fun getSbomDetails(projectPath: String): SbomDetails {
        val bom = buildBom(projectPath)
        val rawXml = BomGeneratorFactory.createXml(Version.VERSION_15, bom).toXmlString()
        val rawJson = BomGeneratorFactory.createJson(Version.VERSION_15, bom).toJsonString()

        val pomFile = File(projectPath)
        val model = try { modelResolver.resolveEffectiveModel(pomFile) } catch(e: Exception) { null }

        val components = if (model != null) {
            try {
                val rootNode = modelResolver.resolveDependencyTree(
                    model.groupId ?: model.parent?.groupId ?: "",
                    model.artifactId,
                    model.version ?: model.parent?.version ?: ""
                )
                val rootComp = mapNode(rootNode)
                rootComp.dependencies.ifEmpty { declaredDependencies(model) }
            } catch (e: Exception) {
                declaredDependencies(model).ifEmpty {
                    // Final fallback for reactor modules collected while building the CycloneDX BOM.
                    bom.components?.map { comp ->
                        SbomComponent(
                            groupId = comp.group,
                            artifactId = comp.name,
                            version = comp.version,
                            scope = comp.scope?.name,
                            type = comp.type?.name,
                            description = comp.description,
                            licenses = comp.licenseChoice?.licenses?.mapNotNull { it.name ?: it.id } ?: emptyList()
                        )
                    } ?: emptyList()
                }
            }
        } else {
            emptyList()
        }

        return SbomDetails(components, rawXml, rawJson)
    }

    private fun declaredDependencies(model: Model): List<SbomComponent> {
        return model.dependencies.orEmpty().map { dependency ->
            val dependencyModel = try {
                modelResolver.resolveModel(dependency.groupId, dependency.artifactId, dependency.version)
            } catch (exception: Exception) {
                null
            }

            SbomComponent(
                groupId = dependency.groupId,
                artifactId = dependency.artifactId,
                version = dependency.version,
                scope = dependency.scope,
                type = dependency.type,
                description = dependencyModel?.description,
                licenses = dependencyModel?.licenses?.mapNotNull { it.name } ?: emptyList()
            )
        }
    }

    private fun mapNode(node: org.eclipse.aether.graph.DependencyNode): SbomComponent {
        val artifact = node.artifact
        val model = try {
            modelResolver.resolveModel(artifact.groupId, artifact.artifactId, artifact.version)
        } catch (e: Exception) {
            null
        }

        return SbomComponent(
            groupId = artifact.groupId,
            artifactId = artifact.artifactId,
            version = artifact.version,
            scope = node.dependency?.scope,
            type = artifact.extension,
            description = model?.description,
            licenses = model?.licenses?.mapNotNull { it.name } ?: emptyList(),
            dependencies = node.children.map { mapNode(it) }
        )
    }

    private fun buildBom(projectPath: String): Bom {
        val pomFile = File(projectPath).absoluteFile
        val model = modelResolver.resolveEffectiveModel(pomFile)
        val bom = Bom()
        val groupId = model.groupId ?: model.parent?.groupId ?: ""
        val artifactId = model.artifactId
        val version = model.version ?: model.parent?.version ?: ""
        val rootComponent = createComponent(groupId, artifactId, version)
        extractMetadata(rootComponent, model)
        bom.metadata = org.cyclonedx.model.Metadata().apply {
            timestamp = java.util.Date()
            component = rootComponent
        }
        val context = BomBuildContext()
        processProject(model, true, context)
        processReactorProjects(findReactorProjects(projectPath), pomFile, context)
        context.components.values.forEach(bom::addComponent)
        addDependencyGraph(bom, context.dependencies)
        return bom
    }

    private data class BomBuildContext(
        val components: MutableMap<String, Component> = mutableMapOf(),
        val dependencies: MutableMap<String, MutableSet<String>> = mutableMapOf()
    )

    private fun processProject(model: Model, isRoot: Boolean, context: BomBuildContext) {
        val groupId = model.groupId ?: model.parent?.groupId ?: ""
        val artifactId = model.artifactId
        val version = model.version ?: model.parent?.version ?: ""
        val projectKey = "$groupId:$artifactId:$version"
        if (!isRoot) {
            context.components[projectKey] = createComponent(groupId, artifactId, version).also {
                extractMetadata(it, model)
            }
        }
        try {
            walkDependencyTree(modelResolver.resolveDependencyTree(groupId, artifactId, version), projectKey, context)
        } catch (e: Exception) {
            addFlatDependencies(groupId, artifactId, version, projectKey, context)
        }
    }

    private fun walkDependencyTree(
        node: org.eclipse.aether.graph.DependencyNode,
        projectKey: String,
        context: BomBuildContext
    ) {
        val artifact = node.artifact
        val nodeKey = "${artifact.groupId}:${artifact.artifactId}:${artifact.version}"
        if (nodeKey != projectKey && nodeKey !in context.components) {
            context.components[nodeKey] = componentForDependency(node)
        }
        val parentRef = packageUrl(artifact)
        node.children.forEach { child ->
            context.dependencies.computeIfAbsent(parentRef) { mutableSetOf() }.add(packageUrl(child.artifact))
            walkDependencyTree(child, projectKey, context)
        }
    }

    private fun componentForDependency(node: org.eclipse.aether.graph.DependencyNode): Component {
        val artifact = node.artifact
        val component = createComponent(artifact.groupId, artifact.artifactId, artifact.version, artifact.extension)
        component.scope = mapScope(node.dependency?.scope)
        if ("${artifact.groupId}:${artifact.artifactId}:${artifact.version}" in workspaceProjects) {
            component.hashes = getHashes(artifact.file)
        }
        try {
            extractMetadata(component, modelResolver.resolveModel(artifact.groupId, artifact.artifactId, artifact.version))
        } catch (e: Exception) {
            // Metadata is optional; retain the dependency coordinates when its POM is unavailable.
        }
        return component
    }

    private fun addFlatDependencies(
        groupId: String,
        artifactId: String,
        version: String,
        projectKey: String,
        context: BomBuildContext
    ) {
        try {
            modelResolver.resolveAllDependencies(groupId, artifactId, version).forEach { dependency ->
                val artifact = dependency.artifact
                val key = "${artifact.groupId}:${artifact.artifactId}:${artifact.version}"
                context.components.computeIfAbsent(key) {
                    createComponent(artifact.groupId, artifact.artifactId, artifact.version).also {
                        it.scope = mapScope(dependency.scope)
                        it.bomRef = key
                    }
                }
                context.dependencies.computeIfAbsent(projectKey) { mutableSetOf() }.add(key)
            }
        } catch (e: Exception) {
            // The project component remains useful even if dependency resolution fails completely.
        }
    }

    private fun processReactorProjects(files: List<File>, rootPom: File, context: BomBuildContext) {
        files.filterNot { it.absolutePath == rootPom.absolutePath }.forEach { file ->
            try {
                processProject(modelResolver.resolveEffectiveModel(file), false, context)
            } catch (e: Exception) {
                // A reactor module without a resolvable model is omitted from the generated BOM.
            }
        }
    }

    private fun createComponent(groupId: String, artifactId: String, version: String, type: String = "jar") =
        Component().apply {
            group = groupId
            name = artifactId
            this.version = version
            this.type = Component.Type.LIBRARY
            bomRef = "pkg:maven/$groupId/$artifactId@$version?type=$type"
            purl = bomRef
        }

    private fun packageUrl(artifact: org.eclipse.aether.artifact.Artifact): String =
        "pkg:maven/${artifact.groupId}/${artifact.artifactId}@${artifact.version}?type=${artifact.extension ?: "jar"}"

    private fun addDependencyGraph(bom: Bom, dependencyGraph: Map<String, Set<String>>) {
        dependencyGraph.forEach { (parentRef, childRefs) ->
            val dep = org.cyclonedx.model.Dependency(parentRef)
            childRefs.forEach { childRef ->
                dep.addDependency(org.cyclonedx.model.Dependency(childRef))
            }
            bom.addDependency(dep)
        }
    }

    private fun mapScope(scope: String?): Component.Scope? {
        return when (scope?.lowercase()) {
            "compile" -> Component.Scope.REQUIRED
            "runtime" -> Component.Scope.REQUIRED
            "provided" -> Component.Scope.OPTIONAL
            "test" -> Component.Scope.EXCLUDED
            else -> Component.Scope.REQUIRED
        }
    }

    private fun getHashes(file: File?): List<Hash> {
        if (file == null || !file.exists() || !file.isFile) return emptyList()
        return try {
            val bytes = file.readBytes()
            val algorithms = listOf("MD5", "SHA-1", "SHA-256", "SHA-512")
            algorithms.map { alg ->
                val digest = MessageDigest.getInstance(alg)
                val hashValue = digest.digest(bytes)
                Hash(alg, hashValue.joinToString("") { "%02x".format(it) })
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun extractMetadata(component: Component, model: Model) {
        component.description = model.description
        createLicenseChoice(model)?.let { component.licenseChoice = it }
        model.organization?.let { component.publisher = it.name }
        if (model.url != null) {
            val extRef = ExternalReference()
            extRef.type = ExternalReference.Type.WEBSITE
            extRef.url = model.url
            component.addExternalReference(extRef)
        }

        if (model.scm != null) {
            val scmRef = ExternalReference()
            scmRef.type = ExternalReference.Type.VCS
            scmRef.url = model.scm.url ?: model.scm.connection
            component.addExternalReference(scmRef)
        }
    }

    @Suppress("DEPRECATION")
    private fun createLicenseChoice(model: Model): LicenseChoice? {
        val licenses = model.licenses.mapNotNull { source -> createLicense(source.name, source.url) }
        if (licenses.isEmpty()) return null
        return LicenseChoice().also { choice -> licenses.forEach(choice::addLicense) }
    }

    private fun createLicense(name: String?, url: String?): License? {
        if (name == null) return null
        return License().apply {
            when {
                name.contains("Apache License, Version 2.0", true) || name == "Apache-2.0" -> id = "Apache-2.0"
                name.contains("MIT License", true) || name == "MIT" -> id = "MIT"
                else -> this.name = name
            }
            this.url = url
        }
    }

    private fun findReactorProjects(projectPath: String): List<File> {
        val currentFile = File(projectPath).absoluteFile
        val currentDirPath = currentFile.parentFile.absolutePath

        return workspaceProjects.values.filter { file ->
            val filePath = file.absolutePath
            // Include the project itself and any sub-projects (modules)
            filePath == currentFile.absolutePath || filePath.startsWith(currentDirPath + File.separator)
        }
    }

    fun setWorkspace(workspaceMap: Map<String, File>) {
        this.workspaceProjects = workspaceMap
        modelResolver = MavenModelResolver(workspaceMap)
    }
}
