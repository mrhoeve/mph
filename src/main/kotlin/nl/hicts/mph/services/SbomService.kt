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
import org.cyclonedx.model.OrganizationalContact
import org.cyclonedx.model.OrganizationalEntity
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
                rootComp.dependencies
            } catch (e: Exception) {
                // FALLBACK: Use CycloneDX Bom components if Aether fails
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
        } else {
            emptyList()
        }

        return SbomDetails(components, rawXml, rawJson)
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
        val rootComponent = Component()
        val groupId = model.groupId ?: model.parent?.groupId ?: ""
        val artifactId = model.artifactId
        val version = model.version ?: model.parent?.version ?: ""

        rootComponent.group = groupId
        rootComponent.name = artifactId
        rootComponent.version = version
        rootComponent.type = Component.Type.LIBRARY
        rootComponent.bomRef = "pkg:maven/$groupId/$artifactId@$version?type=jar"
        rootComponent.purl = rootComponent.bomRef
        extractMetadata(rootComponent, model)

        bom.metadata = org.cyclonedx.model.Metadata().apply {
            timestamp = java.util.Date()
            component = rootComponent
        }

        val reactorProjects = findReactorProjects(projectPath)
        val allComponents = mutableMapOf<String, Component>()
        val dependencyGraph = mutableMapOf<String, MutableSet<String>>()

        // Function to process a project (root or reactor module)
        fun processProject(m: Model, isRoot: Boolean) {
            val pGroupId = m.groupId ?: m.parent?.groupId ?: ""
            val pArtifactId = m.artifactId
            val pVersion = m.version ?: m.parent?.version ?: ""
            val pKey = "$pGroupId:$pArtifactId:$pVersion"

            if (!isRoot) {
                val comp = Component()
                comp.group = pGroupId
                comp.name = pArtifactId
                comp.version = pVersion
                comp.type = Component.Type.LIBRARY
                comp.bomRef = "pkg:maven/$pGroupId/$pArtifactId@$pVersion?type=jar"
                comp.purl = comp.bomRef
                extractMetadata(comp, m)
                allComponents[pKey] = comp
            }

            try {
                val rootNode = modelResolver.resolveDependencyTree(pGroupId, pArtifactId, pVersion)

                fun walk(node: org.eclipse.aether.graph.DependencyNode) {
                    val art = node.artifact
                    val nodeKey = "${art.groupId}:${art.artifactId}:${art.version}"

                    // If it's not the project itself, add as component
                    if (nodeKey != pKey) {
                        if (!allComponents.containsKey(nodeKey)) {
                            val comp = Component()
                            comp.group = art.groupId
                            comp.name = art.artifactId
                            comp.version = art.version
                            comp.scope = mapScope(node.dependency?.scope)
                            comp.type = Component.Type.LIBRARY
                            comp.bomRef = "pkg:maven/${art.groupId}/${art.artifactId}@${art.version}?type=${art.extension ?: "jar"}"
                            comp.purl = comp.bomRef
                            comp.hashes = getHashes(art.file)

                            // Try to get more metadata if it's a local project or we can resolve its model
                            try {
                                val depModel = modelResolver.resolveModel(art.groupId, art.artifactId, art.version)
                                extractMetadata(comp, depModel)
                            } catch (e: Exception) {
                                // Ignore if metadata cannot be fetched
                            }

                            allComponents[nodeKey] = comp
                        }
                    }

                    // Build dependency relationships
                    node.children.forEach { child ->
                        val childArt = child.artifact
                        val childKey = "${childArt.groupId}:${childArt.artifactId}:${childArt.version}"
                        val parentRef = "pkg:maven/${art.groupId}/${art.artifactId}@${art.version}?type=${art.extension ?: "jar"}"
                        val childRef = "pkg:maven/${childArt.groupId}/${childArt.artifactId}@${childArt.version}?type=${childArt.extension ?: "jar"}"
                        dependencyGraph.computeIfAbsent(parentRef) { mutableSetOf() }.add(childRef)
                        walk(child)
                    }
                }
                walk(rootNode)
            } catch (e: Exception) {
                // Fallback to resolveAllDependencies if tree resolution fails
                try {
                    val deps = modelResolver.resolveAllDependencies(pGroupId, pArtifactId, pVersion)
                    deps.forEach { dep ->
                        val art = dep.artifact
                        val depKey = "${art.groupId}:${art.artifactId}:${art.version}"
                        if (!allComponents.containsKey(depKey)) {
                            val comp = Component()
                            comp.group = art.groupId
                            comp.name = art.artifactId
                            comp.version = art.version
                            comp.scope = mapScope(dep.scope)
                            comp.type = Component.Type.LIBRARY
                            comp.bomRef = depKey
                            allComponents[depKey] = comp
                        }
                        dependencyGraph.computeIfAbsent(pKey) { mutableSetOf() }.add(depKey)
                    }
                } catch (ee: Exception) {
                    // Ignore
                }
            }
        }

        // Process root project
        processProject(model, true)

        // Process other reactor projects
        reactorProjects.forEach { file ->
            if (file.absolutePath != pomFile.absolutePath) {
                try {
                    val m = modelResolver.resolveEffectiveModel(file)
                    processProject(m, false)
                } catch (e: Exception) {}
            }
        }

        // Add all found components to the BOM
        allComponents.values.forEach { bom.addComponent(it) }

        // Add dependency relationships to the BOM
        dependencyGraph.forEach { (parentRef, childRefs) ->
            val dep = org.cyclonedx.model.Dependency(parentRef)
            childRefs.forEach { childRef ->
                dep.addDependency(org.cyclonedx.model.Dependency(childRef))
            }
            bom.addDependency(dep)
        }

        return bom
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

    private fun extractMetadata(component: Component, model: Model) {
        component.description = model.description

        val licenses = model.licenses.mapNotNull { l ->
            val license = License()
            if (l.name != null) {
                if (l.name.contains("Apache License, Version 2.0", true) || l.name == "Apache-2.0") {
                    license.id = "Apache-2.0"
                } else if (l.name.contains("MIT License", true) || l.name == "MIT") {
                    license.id = "MIT"
                } else {
                    license.name = l.name
                }
            } else {
                return@mapNotNull null
            }
            license.url = l.url
            val lc = LicenseChoice()
            lc.addLicense(license)
            lc
        }
        if (licenses.isNotEmpty()) {
            val choice = LicenseChoice()
            licenses.forEach { l ->
                l.licenses?.forEach { choice.addLicense(it) }
            }
            component.licenseChoice = choice
        }

        if (model.organization != null) {
            val org = OrganizationalEntity()
            org.name = model.organization.name
            // OrganizationalEntity.urls is a List<String> in recent CycloneDX versions, but it might be just urls
            // Let's check how to set it if addUrl is missing.
            // In some versions it's setUrls(List<String>)
            try {
                val method = org.javaClass.getMethod("setUrls", List::class.java)
                method.invoke(org, listOf(model.organization.url))
            } catch (e: Exception) {
                // Ignore if not found
            }
            component.publisher = model.organization.name
        }

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
