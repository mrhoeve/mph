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
    val licenses: List<String> = emptyList()
)

data class SbomDetails(
    val components: List<SbomComponent>
)

@Service
class SbomService {
    private var modelResolver = MavenModelResolver()

    fun generateSbom(projectPath: String, format: String): String {
        val bom = buildBom(projectPath)
        return if (format.lowercase() == "xml") {
            BomGeneratorFactory.createXml(Version.VERSION_15, bom).toXmlString()
        } else {
            BomGeneratorFactory.createJson(Version.VERSION_15, bom).toJsonString()
        }
    }

    fun getSbomDetails(projectPath: String): SbomDetails {
        val pomFile = File(projectPath)
        val model = modelResolver.resolveEffectiveModel(pomFile)
        
        val artifacts = try {
            modelResolver.resolveDependencies(model.groupId ?: model.parent?.groupId ?: "", model.artifactId, model.version ?: model.parent?.version ?: "")
        } catch (e: Exception) {
            // Fallback to direct dependencies if transitive resolution fails
            return SbomDetails(model.dependencies.map { dep ->
                SbomComponent(
                    groupId = dep.groupId,
                    artifactId = dep.artifactId,
                    version = dep.version,
                    scope = dep.scope,
                    type = dep.type
                )
            })
        }

        return SbomDetails(artifacts.map { artifact ->
            SbomComponent(
                groupId = artifact.groupId,
                artifactId = artifact.artifactId,
                version = artifact.version,
                scope = null, // Aether Artifact doesn't easily expose scope here
                type = artifact.extension
            )
        })
    }

    private fun buildBom(projectPath: String): Bom {
        val pomFile = File(projectPath)
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

        val artifacts = try {
            modelResolver.resolveDependencies(rootComponent.group, rootComponent.name, rootComponent.version)
        } catch (e: Exception) {
            emptyList()
        }

        artifacts.forEach { artifact ->
            val component = Component()
            component.group = artifact.groupId
            component.name = artifact.artifactId
            component.version = artifact.version
            component.type = Component.Type.LIBRARY
            bom.addComponent(component)
        }

        return bom
    }
    
    fun setWorkspace(workspaceMap: Map<String, File>) {
        modelResolver = MavenModelResolver(workspaceMap)
    }
}
