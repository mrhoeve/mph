package nl.hicts.mph.intellij.services

import com.intellij.openapi.util.JDOMUtil
import nl.hicts.mph.intellij.model.MavenCoordinates
import java.util.Properties

data class PomDependencyDeclarations(
    val dependencies: Set<MavenCoordinates>,
    val managedDependencies: Set<MavenCoordinates>,
) {
    companion object {
        fun parse(content: String, properties: Properties = Properties()): PomDependencyDeclarations {
            val project = JDOMUtil.load(content)
            val direct = project.child("dependencies").coordinates(properties)
            val managed = project.child("dependencyManagement")?.child("dependencies").coordinates(properties)
            return PomDependencyDeclarations(direct, managed)
        }

        private fun org.jdom.Element?.coordinates(properties: Properties): Set<MavenCoordinates> =
            this?.children.orEmpty().asSequence().filter { it.name == "dependency" }.mapNotNull { dependency ->
                val groupId = dependency.childText("groupId")?.resolve(properties)?.takeIf(String::isNotBlank)
                val artifactId = dependency.childText("artifactId")?.resolve(properties)?.takeIf(String::isNotBlank)
                if (groupId == null || artifactId == null) null else MavenCoordinates(groupId, artifactId)
            }.toSet()

        private fun org.jdom.Element?.child(name: String): org.jdom.Element? =
            this?.children?.firstOrNull { it.name == name }

        private fun org.jdom.Element.childText(name: String): String? =
            child(name)?.textTrim

        private fun String.resolve(properties: Properties): String {
            var resolved = this
            PROPERTY.findAll(this).forEach { match ->
                properties.getProperty(match.groupValues[1])?.let { value -> resolved = resolved.replace(match.value, value) }
            }
            return resolved
        }

        private val PROPERTY = Regex("\\$\\{([^}]+)}")
    }
}
