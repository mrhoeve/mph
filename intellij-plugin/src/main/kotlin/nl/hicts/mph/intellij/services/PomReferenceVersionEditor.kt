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
        var missingVersionCount = 0

        if (MavenReferenceKind.PARENT in referenceKinds) {
            tagMatches(content, "parent", comments).firstOrNull { matchesCoordinates(it.value, target) }?.let { parent ->
                val version = elementMatch(parent.value, "version")
                if (version == null) {
                    missingVersionCount++
                } else {
                    collectVersionReplacement(parent.range.first, version, newVersion, replacements, referencedProperties)
                }
            }
        }

        if (MavenReferenceKind.DEPENDENCY in referenceKinds ||
            MavenReferenceKind.MANAGED_DEPENDENCY in referenceKinds
        ) {
            tagMatches(content, "dependency", comments).forEach { dependency ->
                val inManagedSection = dependencyManagement.any { dependency.range.first in it }
                val requested = if (inManagedSection) {
                    MavenReferenceKind.MANAGED_DEPENDENCY in referenceKinds
                } else {
                    MavenReferenceKind.DEPENDENCY in referenceKinds &&
                        pluginBlocks.none { dependency.range.first in it }
                }
                if (!requested || !matchesCoordinates(dependency.value, target)) return@forEach

                val version = elementMatch(dependency.value, "version")
                if (version == null) {
                    if (inManagedSection || MavenReferenceKind.MANAGED_DEPENDENCY !in referenceKinds) {
                        missingVersionCount++
                    }
                } else {
                    collectVersionReplacement(
                        dependency.range.first,
                        version,
                        newVersion,
                        replacements,
                        referencedProperties,
                    )
                }
            }
        }

        var updatedContent = applyReplacements(content, replacements)
        var updatedReferenceCount = replacements.size
        val unresolvedProperties = linkedSetOf<String>()

        referencedProperties.forEach { (propertyName, referenceCount) ->
            val propertyUpdate = updateProperty(updatedContent, propertyName, newVersion)
            updatedContent = propertyUpdate.content
            if (propertyUpdate.found) {
                if (propertyUpdate.changed) updatedReferenceCount += referenceCount
            } else {
                unresolvedProperties += propertyName
            }
        }

        return PomReferenceUpdate(
            content = updatedContent,
            updatedReferenceCount = updatedReferenceCount,
            unresolvedProperties = unresolvedProperties,
            missingVersionCount = missingVersionCount,
        )
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
}
