package nl.hicts.mph.intellij.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import nl.hicts.mph.intellij.model.MavenCoordinates
import nl.hicts.mph.intellij.model.MavenProjectInfo
import nl.hicts.mph.intellij.model.MavenReferenceKind
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Files
import java.nio.file.Path

data class ManagedVersionProperty(
    val name: String,
    val value: String,
    val source: String,
    val isOverridden: Boolean,
    val comment: String? = null,
)

data class SpringBootVersionReference(
    val currentVersion: String,
    val coordinates: MavenCoordinates,
    val kind: MavenReferenceKind,
)

data class ManagedVersionAnalysis(
    val project: MavenProjectInfo,
    val properties: List<ManagedVersionProperty>,
    val springBoot: SpringBootVersionReference?,
)

object ManagedPropertyFilter {
    fun filter(
        properties: List<ManagedVersionProperty>,
        overridesOnly: Boolean,
        query: String,
    ): List<ManagedVersionProperty> = properties.filter { property ->
        (!overridesOnly || property.isOverridden) &&
            (query.isBlank() || property.name.contains(query, ignoreCase = true) ||
                property.value.contains(query, ignoreCase = true))
    }
}

@Service(Service.Level.PROJECT)
class ManagedVersionService(
    private val project: Project,
) {
    fun inspect(projectInfo: MavenProjectInfo): ManagedVersionAnalysis {
        val mavenProject = MavenProjectsManager.getInstance(project).projects.firstOrNull {
            normalized(it.file.path) == normalized(projectInfo.pomPath)
        } ?: throw IllegalArgumentException("${projectInfo.artifactId} is no longer linked as a Maven project.")
        val localProperties = PomReferenceVersionEditor.localProperties(currentContent(projectInfo.pomPath))
            .associateBy(LocalPomProperty::name)
        val effectiveProperties = mavenProject.properties.stringPropertyNames()
            .filter(::isVersionProperty)
            .associateWith { name -> mavenProject.properties.getProperty(name).orEmpty() }
        val propertyNames = (effectiveProperties.keys + localProperties.keys.filter(::isVersionProperty)).sorted()
        val properties = propertyNames.map { name ->
            val local = localProperties[name]
            ManagedVersionProperty(
                name = name,
                value = effectiveProperties[name] ?: local?.value.orEmpty(),
                source = if (local != null) "Local POM" else "Inherited Maven model",
                isOverridden = local != null,
                comment = local?.comment,
            )
        }
        val parent = mavenProject.parentId?.takeIf {
            it.groupId == SPRING_BOOT_GROUP && it.artifactId == SPRING_BOOT_PARENT
        }?.let {
            SpringBootVersionReference(
                it.version.orEmpty(),
                MavenCoordinates(it.groupId.orEmpty(), it.artifactId.orEmpty()),
                MavenReferenceKind.PARENT,
            )
        }
        val bom = mavenProject.managedDependencies().values.firstOrNull {
            it.groupId == SPRING_BOOT_GROUP && it.artifactId == SPRING_BOOT_DEPENDENCIES
        }?.let {
            SpringBootVersionReference(
                it.version.orEmpty(),
                MavenCoordinates(it.groupId, it.artifactId),
                MavenReferenceKind.MANAGED_DEPENDENCY,
            )
        }
        return ManagedVersionAnalysis(projectInfo, properties, parent ?: bom)
    }

    fun overrideProperty(projectInfo: MavenProjectInfo, name: String, value: String, comment: String?) {
        updateDocument(projectInfo.pomPath, "Override Maven property $name") { content ->
            PomReferenceVersionEditor.upsertProperty(content, name, value, comment).content
        }
    }

    fun removeOverride(projectInfo: MavenProjectInfo, name: String) {
        updateDocument(projectInfo.pomPath, "Remove Maven property override $name") { content ->
            PomReferenceVersionEditor.removeProperty(content, name).content
        }
    }

    fun upgradeSpringBoot(projectInfo: MavenProjectInfo, reference: SpringBootVersionReference, newVersion: String) {
        updateDocument(projectInfo.pomPath, "Upgrade Spring Boot to $newVersion") { content ->
            PomReferenceVersionEditor.update(content, reference.coordinates, newVersion, setOf(reference.kind)).content
        }
    }

    private fun updateDocument(pomPath: String, commandName: String, transform: (String) -> String) {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(pomPath)
            ?: throw IllegalArgumentException("pom.xml is unavailable: $pomPath")
        val manager = FileDocumentManager.getInstance()
        val document = manager.getDocument(virtualFile)
            ?: throw IllegalArgumentException("pom.xml cannot be edited: $pomPath")
        val updated = transform(document.text)
        if (updated == document.text) return
        WriteCommandAction.writeCommandAction(project).withName(commandName).run<RuntimeException> {
            document.setText(updated)
            manager.saveDocument(document)
        }
    }

    private fun currentContent(pomPath: String): String {
        val application = ApplicationManager.getApplication()
        if (application != null && (application.isDispatchThread || application.isReadAccessAllowed)) {
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(pomPath)
            val document = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) }
            if (document != null) return document.text
        }
        return Files.readString(Path.of(pomPath))
    }

    private fun isVersionProperty(name: String): Boolean = name.contains("version", ignoreCase = true)
    private fun normalized(path: String): Path = Path.of(path).toAbsolutePath().normalize()

    private companion object {
        const val SPRING_BOOT_GROUP = "org.springframework.boot"
        const val SPRING_BOOT_PARENT = "spring-boot-starter-parent"
        const val SPRING_BOOT_DEPENDENCIES = "spring-boot-dependencies"
    }
}
