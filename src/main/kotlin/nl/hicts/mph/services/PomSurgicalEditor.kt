package nl.hicts.mph.services

import java.io.File
import java.nio.charset.StandardCharsets

object PomSurgicalEditor {

    class Session(var content: String) {
        var modified = false

        fun updateProjectVersion(newVersion: String) {
            val excludeTags = listOf("parent", "dependencies", "dependencyManagement", "build", "profiles", "properties", "reporting", "modules", "repositories", "pluginRepositories", "distributionManagement")
            val excludeRanges = excludeTags.flatMap { tag ->
                Regex("(?s)<$tag>.*?</$tag>").findAll(content).map { it.range }
            }
            
            val versionRegex = Regex("<version>(.*?)</version>")
            val matches = versionRegex.findAll(content)
            
            for (match in matches) {
                if (excludeRanges.none { range -> match.range.first in range }) {
                    val oldVersion = match.groups[1]!!.value
                    if (oldVersion != newVersion) {
                        content = content.replaceRange(match.groups[1]!!.range, newVersion)
                        modified = true
                    }
                    return
                }
            }
        }

        fun updateParentVersion(groupId: String, artifactId: String, newVersion: String) {
            val parentRegex = Regex("(?s)<parent>.*?</parent>")
            val parentMatch = parentRegex.find(content) ?: return
            val parentContent = parentMatch.value
            
            if (containsElement(parentContent, "groupId", groupId) && containsElement(parentContent, "artifactId", artifactId)) {
                val versionRegex = Regex("<version>(.*?)</version>")
                val versionMatch = versionRegex.find(parentContent) ?: return
                
                val oldVersion = versionMatch.groups[1]!!.value
                if (oldVersion != newVersion) {
                    val versionRange = versionMatch.groups[1]!!.range
                    val globalVersionRange = IntRange(parentMatch.range.first + versionRange.first, parentMatch.range.first + versionRange.last)
                    content = content.replaceRange(globalVersionRange, newVersion)
                    modified = true
                }
            }
        }

        fun updateProperty(propertyName: String, newValue: String) {
            val propertiesBlocks = Regex("(?s)<properties>.*?</properties>").findAll(content)
            for (propBlock in propertiesBlocks) {
                val propRegex = Regex("<${Regex.escape(propertyName)}>(.*?)</${Regex.escape(propertyName)}>")
                val match = propRegex.find(propBlock.value)
                if (match != null) {
                    val oldValue = match.groups[1]!!.value
                    if (oldValue != newValue) {
                        val globalRange = IntRange(propBlock.range.first + match.groups[1]!!.range.first, propBlock.range.first + match.groups[1]!!.range.last)
                        content = content.replaceRange(globalRange, newValue)
                        modified = true
                    }
                    // We only update the first one we find in the current file to stay safe, 
                    // though usually property names are unique per block.
                    return
                }
            }
        }

        fun upsertProperty(propertyName: String, newValue: String, remark: String?) {
            val propertiesMatch = Regex("(?s)<properties>.*?</properties>").find(content)
            
            val remarkBlock = if (remark != null && remark.isNotBlank()) "<!-- $remark -->\n        " else ""
            val newPropLine = "$remarkBlock<$propertyName>$newValue</$propertyName>"

            if (propertiesMatch != null) {
                val propertiesContent = propertiesMatch.value
                val propRegex = Regex("(<!--.*?-->[ \t]*\r?\n[ \t]*)?<${Regex.escape(propertyName)}>(?s:.*?)</${Regex.escape(propertyName)}>")
                val existingPropMatch = propRegex.find(propertiesContent)

                if (existingPropMatch != null) {
                    val globalRange = IntRange(propertiesMatch.range.first + existingPropMatch.range.first, propertiesMatch.range.first + existingPropMatch.range.last)
                    content = content.replaceRange(globalRange, newPropLine)
                } else {
                    val insertIndex = propertiesMatch.range.first + propertiesContent.lastIndexOf("</properties>")
                    val indent = "        "
                    content = StringBuilder(content).insert(insertIndex, "    $indent$newPropLine\n    ").toString()
                }
                modified = true
            } else {
                // If no properties tag, insert after project/artifactId (rough heuristic)
                val modelVersionEnd = content.indexOf("</modelVersion>")
                val insertIndex = if (modelVersionEnd != -1) content.indexOf(">", modelVersionEnd) + 1 else content.indexOf(">") + 1
                val propertiesBlock = "\n    <properties>\n        $newPropLine\n    </properties>\n"
                content = StringBuilder(content).insert(insertIndex, propertiesBlock).toString()
                modified = true
            }
        }

        fun removeProperty(propertyName: String) {
            val propertiesMatch = Regex("(?s)<properties>.*?</properties>").find(content) ?: return
            val propertiesContent = propertiesMatch.value
            // Regex to find property and its optional preceding comment, including leading whitespace/newline
            // We match at most one preceding comment line to be safe
            val propRegex = Regex("([ \t]*\r?\n[ \t]*)?(<!--.*?-->[ \t]*\r?\n[ \t]*)?<${Regex.escape(propertyName)}>(?s:.*?)</${Regex.escape(propertyName)}>")
            val existingPropMatch = propRegex.find(propertiesContent)

            if (existingPropMatch != null) {
                val globalRange = IntRange(propertiesMatch.range.first + existingPropMatch.range.first, propertiesMatch.range.first + existingPropMatch.range.last)
                content = content.replaceRange(globalRange, "")
                modified = true
            }
        }

        fun updateDependencyVersion(groupId: String, artifactId: String, newVersion: String) {
            val depBlocks = Regex("(?s)<dependency>.*?</dependency>").findAll(content).toList().reversed()
            for (block in depBlocks) {
                val blockContent = block.value
                if (containsElement(blockContent, "groupId", groupId) && containsElement(blockContent, "artifactId", artifactId)) {
                    val versionMatch = Regex("<version>(.*?)</version>").find(blockContent)
                    if (versionMatch != null) {
                        val oldVersion = versionMatch.groups[1]!!.value
                        if (oldVersion != newVersion) {
                            val versionRange = versionMatch.groups[1]!!.range
                            val globalVersionRange = IntRange(block.range.first + versionRange.first, block.range.first + versionRange.last)
                            content = content.replaceRange(globalVersionRange, newVersion)
                            modified = true
                        }
                    }
                }
            }
        }

        private fun containsElement(content: String, tag: String, value: String): Boolean {
            val regex = Regex("<$tag>\\s*${Regex.escape(value)}\\s*</$tag>")
            return regex.containsMatchIn(content)
        }
    }

    fun edit(file: File, action: Session.() -> Unit) {
        val content = file.readText(StandardCharsets.UTF_8)
        val session = Session(content)
        session.action()
        if (session.modified) {
            file.writeText(session.content, StandardCharsets.UTF_8)
        }
    }
}
