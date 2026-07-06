package nl.hicts.mph.services

import nl.hicts.mph.models.MavenProject
import org.apache.maven.model.Model
import org.cyclonedx.Version
import org.cyclonedx.generators.BomGeneratorFactory
import org.cyclonedx.model.Bom
import org.cyclonedx.model.Component
import org.cyclonedx.model.License
import org.cyclonedx.model.LicenseChoice
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Path

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
                bom.components?.map { comp ->
                    SbomComponent(
                        groupId = comp.group,
                        artifactId = comp.name,
                        version = comp.version,
                        scope = comp.scope?.name,
                        type = comp.type?.name,
                        description = comp.description,
                        licenses = comp.licenseChoice?.licenses?.map { it.name ?: it.id } ?: emptyList()
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
        return SbomComponent(
            groupId = artifact.groupId,
            artifactId = artifact.artifactId,
            version = artifact.version,
            scope = node.dependency?.scope,
            type = artifact.extension,
            dependencies = node.children.map { mapNode(it) }
        )
    }

    private fun buildBom(projectPath: String): Bom {
        val pomFile = File(projectPath).absoluteFile
        val model = modelResolver.resolveEffectiveModel(pomFile)
        
        val bom = Bom()
        val rootComponent = Component()
        rootComponent.group = model.groupId ?: model.parent?.groupId
        rootComponent.name = model.artifactId
        rootComponent.version = model.version ?: model.parent?.version
        rootComponent.type = Component.Type.LIBRARY
        bom.metadata = org.cyclonedx.model.Metadata().apply {
            component = rootComponent
        }

        val reactorProjects = findReactorProjects(projectPath)
        val allComponents = mutableMapOf<String, Component>()

        // Add all reactor projects as components (including the main one if multi-module)
        reactorProjects.forEach { file ->
            val m = try { modelResolver.resolveEffectiveModel(file) } catch(e: Exception) { null }
            if (m != null) {
                if (file.absolutePath != pomFile.absolutePath) {
                    val comp = Component()
                    comp.group = m.groupId ?: m.parent?.groupId
                    comp.name = m.artifactId
                    comp.version = m.version ?: m.parent?.version
                    comp.type = Component.Type.LIBRARY
                    bom.addComponent(comp)
                }

                // Add dependencies for each reactor project
                val dependencies = try {
                    modelResolver.resolveAllDependencies(
                        m.groupId ?: m.parent?.groupId ?: "",
                        m.artifactId,
                        m.version ?: m.parent?.version ?: ""
                    )
                } catch (e: Exception) {
                    emptyList()
                }

                dependencies.forEach { dep ->
                    val artifact = dep.artifact
                    val key = "${artifact.groupId}:${artifact.artifactId}:${artifact.version}"
                    if (!allComponents.containsKey(key)) {
                        val component = Component()
                        component.group = artifact.groupId
                        component.name = artifact.artifactId
                        component.version = artifact.version
                        component.scope = mapScope(dep.scope)
                        component.type = Component.Type.LIBRARY
                        allComponents[key] = component
                    }
                }
            }
        }

        allComponents.values.forEach { bom.addComponent(it) }

        return bom
    }

    private fun mapScope(scope: String?): Component.Scope? {
        if (scope == null) return null
        val normalizedScope = scope.uppercase()
        return try {
            Component.Scope.valueOf(normalizedScope)
        } catch (e: Exception) {
            null
        }
    }

    private fun findReactorProjects(projectPath: String): List<File> {
        val currentFile = File(projectPath).absoluteFile
        val rootProjectFile = workspaceProjects.values.filter { file ->
            currentFile.absolutePath.startsWith(file.parentFile.absolutePath)
        }.minByOrNull { it.absolutePath.length } ?: currentFile
        
        return workspaceProjects.values.filter { file ->
            file.absolutePath.startsWith(rootProjectFile.parentFile.absolutePath)
        }
    }
    
    fun setWorkspace(workspaceMap: Map<String, File>) {
        this.workspaceProjects = workspaceMap
        modelResolver = MavenModelResolver(workspaceMap)
    }
}
