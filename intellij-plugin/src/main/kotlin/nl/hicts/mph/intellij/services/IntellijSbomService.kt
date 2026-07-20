package nl.hicts.mph.intellij.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import nl.hicts.mph.intellij.model.MavenProjectInfo
import org.jetbrains.idea.maven.model.MavenArtifact
import org.jetbrains.idea.maven.model.MavenArtifactNode
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

data class SbomComponent(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val type: String,
    val scope: String,
    val resolved: Boolean,
    val children: List<SbomComponent>,
) {
    val coordinates: String
        get() = "$groupId:$artifactId:$version"
    val bomRef: String
        get() = mavenPurl(groupId, artifactId, version)
}

data class SbomAnalysis(
    val project: MavenProjectInfo,
    val dependencies: List<SbomComponent>,
) {
    val components: List<SbomComponent>
        get() = dependencies.flatMap(::flatten).distinctBy(SbomComponent::bomRef)

    private fun flatten(component: SbomComponent): List<SbomComponent> =
        listOf(component) + component.children.flatMap(::flatten)
}

object SbomSearch {
    fun filter(components: List<SbomComponent>, query: String): List<SbomComponent> {
        val search = query.trim().lowercase()
        if (search.isEmpty()) return components
        return components.mapNotNull { component ->
            val matchingChildren = filter(component.children, search)
            if (component.matches(search) || matchingChildren.isNotEmpty()) {
                component.copy(children = matchingChildren)
            } else {
                null
            }
        }
    }

    private fun SbomComponent.matches(query: String): Boolean = sequenceOf(
        groupId,
        artifactId,
        version,
        type,
        scope,
        coordinates,
        "$groupId:$artifactId:$version:$scope",
    ).any { it.lowercase().contains(query) }
}

@Service(Service.Level.PROJECT)
class IntellijSbomService(
    private val project: Project,
) {
    fun inspect(projectInfo: MavenProjectInfo): SbomAnalysis {
        val mavenProjects = MavenProjectsManager.getInstance(project).projects
        val mavenProject = mavenProjects.firstOrNull {
            Path.of(it.file.path).toAbsolutePath().normalize() == Path.of(projectInfo.pomPath).toAbsolutePath().normalize()
        } ?: throw IllegalArgumentException("${projectInfo.artifactId} is no longer linked as a Maven project.")
        val includedProjects = includedProjects(mavenProject, mavenProjects)
        val dependencies = if (includedProjects.size == 1) {
            dependencies(mavenProject)
        } else {
            includedProjects.drop(1).map { module ->
                SbomComponent(
                    groupId = module.mavenId.groupId.orEmpty(),
                    artifactId = module.mavenId.artifactId ?: module.displayName,
                    version = module.mavenId.version.orEmpty(),
                    type = module.packaging,
                    scope = "module",
                    resolved = true,
                    children = dependencies(module),
                )
            }
        }
        return SbomAnalysis(projectInfo, dependencies)
    }

    private fun dependencies(mavenProject: MavenProject): List<SbomComponent> =
        SbomDependencyResolver.preferTree(
            mavenProject.dependencyTree.map(::component),
            mavenProject.dependencies.map(::component),
        )

    private fun includedProjects(root: MavenProject, allProjects: List<MavenProject>): List<MavenProject> {
        if (root.modulePaths.isEmpty()) return listOf(root)
        val projectsByPom = allProjects.associateBy { normalized(it.file.path) }
        val included = linkedMapOf(normalized(root.file.path) to root)
        val queue = ArrayDeque<MavenProject>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            current.modulePaths.asSequence()
                .flatMap { modulePath -> modulePomCandidates(current.directoryPath, modulePath) }
                .mapNotNull(projectsByPom::get)
                .filter { normalized(it.file.path) !in included }
                .forEach { module ->
                    included[normalized(module.file.path)] = module
                    queue.add(module)
                }
        }
        return included.values.toList()
    }

    private fun modulePomCandidates(baseDirectory: Path, modulePath: String): Sequence<Path> = sequence {
        val raw = runCatching { Path.of(modulePath) }.getOrNull() ?: return@sequence
        val resolved = if (raw.isAbsolute) raw else baseDirectory.resolve(raw)
        yield(normalized(resolved.toString()))
        yield(normalized(resolved.resolve("pom.xml").toString()))
    }

    private fun normalized(path: String): Path = Path.of(path).toAbsolutePath().normalize()

    private fun component(node: MavenArtifactNode): SbomComponent {
        val artifact = node.artifact
        return SbomComponent(
            groupId = artifact.groupId,
            artifactId = artifact.artifactId,
            version = artifact.version,
            type = artifact.type,
            scope = artifact.scope,
            resolved = artifact.isResolved,
            children = node.dependencies.map(::component),
        )
    }

    private fun component(artifact: MavenArtifact): SbomComponent = SbomComponent(
        groupId = artifact.groupId,
        artifactId = artifact.artifactId,
        version = artifact.version,
        type = artifact.type,
        scope = artifact.scope,
        resolved = artifact.isResolved,
        children = emptyList(),
    )
}

object SbomDependencyResolver {
    fun preferTree(tree: List<SbomComponent>, resolvedDependencies: List<SbomComponent>): List<SbomComponent> =
        (tree.takeIf(List<SbomComponent>::isNotEmpty) ?: resolvedDependencies)
            .distinctBy(SbomComponent::bomRef)
}

object CycloneDxExporter {
    fun json(analysis: SbomAnalysis, timestamp: Instant = Instant.now(), serial: UUID = UUID.randomUUID()): String {
        val rootRef = rootRef(analysis.project)
        val components = analysis.components.joinToString(",") { component ->
            """{"type":"library","bom-ref":"${json(component.bomRef)}","group":"${json(component.groupId)}","name":"${json(component.artifactId)}","version":"${json(component.version)}","purl":"${json(component.bomRef)}","properties":[{"name":"mph:scope","value":"${json(component.scope)}"},{"name":"mph:resolved","value":"${component.resolved}"}]}"""
        }
        val dependencyEntries = buildList {
            add(dependencyJson(rootRef, analysis.dependencies.map(SbomComponent::bomRef)))
            analysis.components.forEach { component ->
                add(dependencyJson(component.bomRef, component.children.map(SbomComponent::bomRef)))
            }
        }.joinToString(",")
        return """{"bomFormat":"CycloneDX","specVersion":"1.5","serialNumber":"urn:uuid:$serial","version":1,"metadata":{"timestamp":"$timestamp","component":{"type":"application","bom-ref":"${json(rootRef)}","group":"${json(analysis.project.groupId.orEmpty())}","name":"${json(analysis.project.artifactId)}","version":"${json(analysis.project.version.orEmpty())}"}},"components":[$components],"dependencies":[$dependencyEntries]}"""
    }

    fun xml(analysis: SbomAnalysis, timestamp: Instant = Instant.now(), serial: UUID = UUID.randomUUID()): String {
        val rootRef = rootRef(analysis.project)
        val components = analysis.components.joinToString("") { component ->
            """<component type="library" bom-ref="${xml(component.bomRef)}"><group>${xml(component.groupId)}</group><name>${xml(component.artifactId)}</name><version>${xml(component.version)}</version><purl>${xml(component.bomRef)}</purl><properties><property name="mph:scope">${xml(component.scope)}</property><property name="mph:resolved">${component.resolved}</property></properties></component>"""
        }
        val dependencies = buildString {
            append(dependencyXml(rootRef, analysis.dependencies.map(SbomComponent::bomRef)))
            analysis.components.forEach { component ->
                append(dependencyXml(component.bomRef, component.children.map(SbomComponent::bomRef)))
            }
        }
        return """<?xml version="1.0" encoding="UTF-8"?><bom xmlns="http://cyclonedx.org/schema/bom/1.5" serialNumber="urn:uuid:$serial" version="1"><metadata><timestamp>$timestamp</timestamp><component type="application" bom-ref="${xml(rootRef)}"><group>${xml(analysis.project.groupId.orEmpty())}</group><name>${xml(analysis.project.artifactId)}</name><version>${xml(analysis.project.version.orEmpty())}</version></component></metadata><components>$components</components><dependencies>$dependencies</dependencies></bom>"""
    }

    private fun rootRef(project: MavenProjectInfo) =
        mavenPurl(project.groupId.orEmpty(), project.artifactId, project.version.orEmpty())

    private fun dependencyJson(reference: String, dependencies: List<String>): String =
        """{"ref":"${json(reference)}","dependsOn":[${dependencies.distinct().joinToString(",") { "\"${json(it)}\"" }}]}"""

    private fun dependencyXml(reference: String, dependencies: List<String>): String =
        "<dependency ref=\"${xml(reference)}\">${dependencies.distinct().joinToString("") { "<dependency ref=\"${xml(it)}\"/>" }}</dependency>"

    private fun json(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }

    private fun xml(value: String): String = value.replace("&", "&amp;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}

private fun mavenPurl(groupId: String, artifactId: String, version: String): String =
    "pkg:maven/${purl(groupId)}/${purl(artifactId)}@${purl(version)}"

private fun purl(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
