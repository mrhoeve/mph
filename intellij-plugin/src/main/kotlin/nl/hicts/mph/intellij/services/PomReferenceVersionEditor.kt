package nl.hicts.mph.intellij.services

import nl.hicts.mph.intellij.model.MavenCoordinates
import nl.hicts.mph.intellij.model.MavenReferenceKind

data class PomReferenceUpdate(
    val content: String,
    val updatedReferenceCount: Int,
    val unresolvedProperties: Set<String>,
    val missingVersionCount: Int,
) {
    val changed: Boolean
        get() = updatedReferenceCount > 0
}

data class PomProjectVersionUpdate(
    val content: String,
    val changed: Boolean,
    val unresolvedProperty: String? = null,
)

data class LocalPomProperty(
    val name: String,
    val value: String,
    val comment: String? = null,
)

data class PomPropertyUpdate(
    val content: String,
    val changed: Boolean,
)

object PomReferenceVersionEditor {
    private val propertyReference = Regex("^\\$\\{([^}]+)}$")

    fun findProjectVersion(content: String): String? {
        val version = projectVersionMatch(content)
            ?.groups?.get(1)?.value?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null
        val propertyName = propertyReference.matchEntire(version)?.groups?.get(1)?.value ?: return version
        return findPropertyValue(content, propertyName)
    }

    fun updateProjectVersion(content: String, newVersion: String): PomProjectVersionUpdate {
        requireSafeVersion(newVersion)
        val version = projectVersionMatch(content)
            ?: return PomProjectVersionUpdate(content, changed = false)
        val value = version.groups[1] ?: return PomProjectVersionUpdate(content, changed = false)
        val oldVersion = value.value.trim()
        val propertyName = propertyReference.matchEntire(oldVersion)?.groups?.get(1)?.value
        if (propertyName != null) {
            val propertyUpdate = updateProperty(content, propertyName, newVersion)
            return PomProjectVersionUpdate(
                content = propertyUpdate.content,
                changed = propertyUpdate.changed,
                unresolvedProperty = propertyName.takeUnless { propertyUpdate.found },
            )
        }
        if (oldVersion == newVersion) return PomProjectVersionUpdate(content, changed = false)
        return PomProjectVersionUpdate(
            content = content.replaceRange(value.range, newVersion),
            changed = true,
        )
    }

    fun localProperties(content: String): List<LocalPomProperty> {
        val comments = ranges(content, "<!--", "-->")
        val properties = tagMatches(content, "properties", comments).firstOrNull() ?: return emptyList()
        val entry = Regex(
            "(?:<!--\\s*(.*?)\\s*-->\\s*)?<([A-Za-z_][A-Za-z0-9_.-]*)>\\s*(.*?)\\s*</\\2>",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val bodyStart = properties.value.indexOf('>').takeIf { it >= 0 }?.plus(1) ?: return emptyList()
        val bodyEnd = properties.value.lastIndexOf("</properties>").takeIf { it >= bodyStart } ?: return emptyList()
        return entry.findAll(properties.value.substring(bodyStart, bodyEnd)).map { match ->
            LocalPomProperty(
                name = match.groups[2]?.value.orEmpty(),
                value = match.groups[3]?.value?.trim().orEmpty(),
                comment = match.groups[1]?.value?.trim()?.takeIf(String::isNotBlank),
            )
        }.toList()
    }

    fun upsertProperty(
        content: String,
        propertyName: String,
        value: String,
        comment: String? = null,
    ): PomPropertyUpdate {
        requireSafePropertyName(propertyName)
        requireSafeVersion(value)
        require(comment?.contains("--") != true) { "An XML comment cannot contain '--'." }
        val existing = updateProperty(content, propertyName, value)
        if (existing.found) return PomPropertyUpdate(existing.content, existing.changed)

        val commentLine = comment?.trim()?.takeIf(String::isNotBlank)?.let { "        <!-- $it -->\n" }.orEmpty()
        val propertyLine = "        <$propertyName>$value</$propertyName>\n"
        val properties = tagMatches(content, "properties", ranges(content, "<!--", "-->"))
            .firstOrNull()
        val updated = if (properties != null) {
            val closing = content.indexOf("</properties>", properties.range.first)
            if (closing < 0) return PomPropertyUpdate(content, changed = false)
            content.replaceRange(closing, closing, commentLine + propertyLine)
        } else {
            val projectClosing = content.lastIndexOf("</project>")
            if (projectClosing < 0) return PomPropertyUpdate(content, changed = false)
            val block = "    <properties>\n$commentLine$propertyLine    </properties>\n"
            content.replaceRange(projectClosing, projectClosing, block)
        }
        return PomPropertyUpdate(updated, changed = true)
    }

    fun removeProperty(content: String, propertyName: String): PomPropertyUpdate {
        requireSafePropertyName(propertyName)
        val properties = tagMatches(content, "properties", ranges(content, "<!--", "-->"))
            .firstOrNull() ?: return PomPropertyUpdate(content, changed = false)
        val property = Regex(
            "(?:[ \\t]*<!--(?:(?!-->).)*-->\\s*)?[ \\t]*<${Regex.escape(propertyName)}>.*?</${Regex.escape(propertyName)}>\\s*",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(properties.value) ?: return PomPropertyUpdate(content, changed = false)
        val absoluteRange = IntRange(
            properties.range.first + property.range.first,
            properties.range.first + property.range.last,
        )
        return PomPropertyUpdate(content.replaceRange(absoluteRange, ""), changed = true)
    }

    fun update(
        content: String,
        target: MavenCoordinates,
        newVersion: String,
        referenceKinds: Set<MavenReferenceKind>,
    ): PomReferenceUpdate {
        requireSafeVersion(newVersion)

        val comments = ranges(content, "<!--", "-->")
        val dependencyManagement = tagRanges(content, "dependencyManagement", comments)
        val pluginBlocks = tagRanges(content, "plugin", comments)
        val replacements = mutableListOf<Replacement>()
        val referencedProperties = linkedMapOf<String, Int>()
        val collector = VersionReplacementCollector(
            target = UpdateTarget(content, target, newVersion, referenceKinds),
            ranges = ReferenceRanges(comments, dependencyManagement, pluginBlocks),
            collected = CollectedReplacements(replacements, referencedProperties),
        )
        val missingVersionCount = collectParentReplacement(collector) + collectDependencyReplacements(collector)

        val propertyResult = updateReferencedProperties(
            applyReplacements(content, replacements), newVersion, referencedProperties, replacements.size,
        )

        return PomReferenceUpdate(
            content = propertyResult.content,
            updatedReferenceCount = propertyResult.updatedReferenceCount,
            unresolvedProperties = propertyResult.unresolvedProperties,
            missingVersionCount = missingVersionCount,
        )
    }

    private fun collectParentReplacement(collector: VersionReplacementCollector): Int {
        if (MavenReferenceKind.PARENT !in collector.target.referenceKinds) return 0
        val parent = tagMatches(collector.target.content, "parent", collector.ranges.comments)
            .firstOrNull { matchesCoordinates(it.value, collector.target.coordinates) }
            ?: return 0
        val version = elementMatch(parent.value, "version") ?: return 1
        collectVersionReplacement(
            parent.range.first,
            version,
            collector.target.newVersion,
            collector.collected.replacements,
            collector.collected.referencedProperties,
        )
        return 0
    }

    private fun collectDependencyReplacements(collector: VersionReplacementCollector): Int {
        if (MavenReferenceKind.DEPENDENCY !in collector.target.referenceKinds &&
            MavenReferenceKind.MANAGED_DEPENDENCY !in collector.target.referenceKinds
        ) return 0
        var missingVersions = 0
        tagMatches(collector.target.content, "dependency", collector.ranges.comments).forEach { dependency ->
            val managed = collector.ranges.dependencyManagement.any { dependency.range.first in it }
            if (!requestedDependency(
                    dependency,
                    managed,
                    collector.target.referenceKinds,
                    collector.ranges.pluginBlocks,
                ) || !matchesCoordinates(dependency.value, collector.target.coordinates)
            ) return@forEach
            val version = elementMatch(dependency.value, "version")
            if (version == null) {
                if (managed || MavenReferenceKind.MANAGED_DEPENDENCY !in collector.target.referenceKinds) missingVersions++
            } else {
                collectVersionReplacement(
                    dependency.range.first,
                    version,
                    collector.target.newVersion,
                    collector.collected.replacements,
                    collector.collected.referencedProperties,
                )
            }
        }
        return missingVersions
    }

    private fun requestedDependency(
        dependency: MatchResult,
        managed: Boolean,
        referenceKinds: Set<MavenReferenceKind>,
        pluginBlocks: List<IntRange>,
    ): Boolean = if (managed) {
        MavenReferenceKind.MANAGED_DEPENDENCY in referenceKinds
    } else {
        MavenReferenceKind.DEPENDENCY in referenceKinds && pluginBlocks.none { dependency.range.first in it }
    }

    private fun updateReferencedProperties(
        initialContent: String,
        newVersion: String,
        referencedProperties: Map<String, Int>,
        initialUpdateCount: Int,
    ): ReferencedPropertyUpdate {
        var content = initialContent
        var updatedReferenceCount = initialUpdateCount
        val unresolvedProperties = linkedSetOf<String>()
        referencedProperties.forEach { (propertyName, referenceCount) ->
            val propertyUpdate = updateProperty(content, propertyName, newVersion)
            content = propertyUpdate.content
            if (!propertyUpdate.found) {
                unresolvedProperties += propertyName
            } else if (propertyUpdate.changed) {
                updatedReferenceCount += referenceCount
            }
        }
        return ReferencedPropertyUpdate(content, updatedReferenceCount, unresolvedProperties)
    }

    private fun collectVersionReplacement(
        blockStart: Int,
        version: MatchResult,
        newVersion: String,
        replacements: MutableList<Replacement>,
        referencedProperties: MutableMap<String, Int>,
    ) {
        val valueGroup = version.groups[1] ?: return
        val oldVersion = valueGroup.value.trim()
        val propertyName = propertyReference.matchEntire(oldVersion)?.groups?.get(1)?.value
        if (propertyName != null) {
            referencedProperties[propertyName] = referencedProperties.getOrDefault(propertyName, 0) + 1
        } else if (oldVersion != newVersion) {
            replacements += Replacement(
                IntRange(blockStart + valueGroup.range.first, blockStart + valueGroup.range.last),
                newVersion,
            )
        }
    }

    private fun updateProperty(content: String, propertyName: String, newVersion: String): PropertyUpdate {
        val comments = ranges(content, "<!--", "-->")
        val propertyBlocks = tagMatches(content, "properties", comments)
        propertyBlocks.forEach { properties ->
            val property = elementMatch(properties.value, Regex.escape(propertyName)) ?: return@forEach
            val valueGroup = property.groups[1] ?: return@forEach
            if (valueGroup.value.trim() == newVersion) return PropertyUpdate(content, found = true, changed = false)
            val range = IntRange(
                properties.range.first + valueGroup.range.first,
                properties.range.first + valueGroup.range.last,
            )
            return PropertyUpdate(content.replaceRange(range, newVersion), found = true, changed = true)
        }
        return PropertyUpdate(content, found = false, changed = false)
    }

    private fun findPropertyValue(content: String, propertyName: String): String? {
        val comments = ranges(content, "<!--", "-->")
        tagMatches(content, "properties", comments).forEach { properties ->
            return elementMatch(properties.value, Regex.escape(propertyName))
                ?.groups?.get(1)?.value?.trim()?.takeIf(String::isNotBlank)
        }
        return null
    }

    private fun projectVersionMatch(content: String): MatchResult? {
        val ignoredTags = listOf(
            "parent",
            "dependencies",
            "dependencyManagement",
            "build",
            "profiles",
            "properties",
            "reporting",
            "repositories",
            "pluginRepositories",
            "distributionManagement",
        )
        val ignoredRanges = ignoredTags.flatMap { tag -> tagRanges(content, tag, emptyList()) }
        return Regex("<version>\\s*(.*?)\\s*</version>", setOf(RegexOption.DOT_MATCHES_ALL))
            .findAll(content)
            .firstOrNull { match -> ignoredRanges.none { match.range.first in it } }
    }

    private fun requireSafeVersion(version: String) {
        require(version.isNotBlank()) { "The target version must not be blank." }
        require(version.none { it == '<' || it == '>' || it == '&' }) {
            "The target version contains characters that are unsafe in XML."
        }
    }

    private fun requireSafePropertyName(name: String) {
        require(Regex("[A-Za-z_][A-Za-z0-9_.-]*").matches(name)) { "The Maven property name is invalid." }
    }

    private fun matchesCoordinates(block: String, target: MavenCoordinates): Boolean =
        containsElement(block, "groupId", target.groupId) &&
            containsElement(block, "artifactId", target.artifactId)

    private fun containsElement(content: String, tag: String, value: String): Boolean =
        Regex("<$tag>\\s*${Regex.escape(value)}\\s*</$tag>").containsMatchIn(content)

    private fun elementMatch(content: String, tag: String): MatchResult? =
        Regex("<$tag>\\s*(.*?)\\s*</$tag>", setOf(RegexOption.DOT_MATCHES_ALL)).find(content)

    private fun tagMatches(content: String, tag: String, ignoredRanges: List<IntRange>): List<MatchResult> =
        Regex("<$tag(?:\\s[^>]*)?>.*?</$tag>", setOf(RegexOption.DOT_MATCHES_ALL))
            .findAll(content)
            .filter { match -> ignoredRanges.none { match.range.first in it } }
            .toList()

    private fun tagRanges(content: String, tag: String, ignoredRanges: List<IntRange>): List<IntRange> =
        tagMatches(content, tag, ignoredRanges).map(MatchResult::range)

    private fun ranges(content: String, start: String, end: String): List<IntRange> =
        Regex("${Regex.escape(start)}.*?${Regex.escape(end)}", RegexOption.DOT_MATCHES_ALL)
            .findAll(content)
            .map(MatchResult::range)
            .toList()

    private fun applyReplacements(content: String, replacements: List<Replacement>): String =
        replacements.sortedByDescending { it.range.first }.fold(content) { current, replacement ->
            current.replaceRange(replacement.range, replacement.value)
        }

    private data class Replacement(val range: IntRange, val value: String)
    private data class PropertyUpdate(val content: String, val found: Boolean, val changed: Boolean)
    private data class ReferencedPropertyUpdate(
        val content: String,
        val updatedReferenceCount: Int,
        val unresolvedProperties: Set<String>,
    )
    private data class VersionReplacementCollector(
        val target: UpdateTarget,
        val ranges: ReferenceRanges,
        val collected: CollectedReplacements,
    )
    private data class UpdateTarget(
        val content: String,
        val coordinates: MavenCoordinates,
        val newVersion: String,
        val referenceKinds: Set<MavenReferenceKind>,
    )
    private data class ReferenceRanges(
        val comments: List<IntRange>,
        val dependencyManagement: List<IntRange>,
        val pluginBlocks: List<IntRange>,
    )
    private data class CollectedReplacements(
        val replacements: MutableList<Replacement>,
        val referencedProperties: MutableMap<String, Int>,
    )
}
