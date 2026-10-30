package nl.hicts.mph.intellij.services

import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Conflict marker text alone cannot distinguish XML elements from comments or CDATA. */
internal object PomVersionConflictCheck {
    @Suppress("kotlin:S5332") // XML parser feature identifiers, not network requests.
    fun onlyVersionTextChanges(first: String, second: String): Boolean = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val parser = factory.newDocumentBuilder().apply { setErrorHandler(DefaultHandler()) }
        val a = parser.parse(InputSource(StringReader(first)))
        val b = parser.parse(InputSource(StringReader(second)))
        a.documentElement.localName == "project" && b.documentElement.localName == "project" && equivalent(a, b)
    }.getOrDefault(false)

    private fun equivalent(a: Node, b: Node): Boolean {
        if (a.nodeType != b.nodeType || a.nodeName != b.nodeName || a.namespaceURI != b.namespaceURI) return false
        if (a.nodeValue != b.nodeValue && !(a.nodeType == Node.TEXT_NODE && versionParent(a.parentNode))) return false
        val attributes = a.attributes
        if ((attributes?.length ?: 0) != (b.attributes?.length ?: 0)) return false
        if (attributes != null && (0 until attributes.length).any { i ->
                val attr = attributes.item(i)
                attr.nodeValue != b.attributes?.getNamedItem(attr.nodeName)?.nodeValue
            }) return false
        val children = a.childNodes
        return children.length == b.childNodes.length && (0 until children.length).all { equivalent(children.item(it), b.childNodes.item(it)) }
    }

    private fun versionParent(parent: Node?): Boolean {
        if (parent?.nodeType != Node.ELEMENT_NODE) return false
        val namespace = parent.ownerDocument.documentElement.namespaceURI
        val elements = generateSequence(parent) { it.parentNode }.filter { it.nodeType == Node.ELEMENT_NODE }.toList().reversed()
        if (elements.any { it.namespaceURI != namespace }) return false
        val path = elements.map { it.localName }.drop(1)
        val scope = if (path.take(2) == listOf("profiles", "profile")) path.drop(2) else path
        if (scope.size == 2 && scope.first() == "properties") {
            val name = scope.last()
            return name.endsWith("version", true) || name in setOf("revision", "changelist", "sha1")
        }
        if (scope == listOf("version") || scope == listOf("parent", "version")) return scope == path
        if (scope in listOf(listOf("dependencies", "dependency", "version"),
                listOf("dependencyManagement", "dependencies", "dependency", "version"),
                listOf("build", "extensions", "extension", "version"))) return true
        return listOf(listOf("build", "plugins", "plugin"), listOf("build", "pluginManagement", "plugins", "plugin"),
            listOf("reporting", "plugins", "plugin")).any { prefix ->
            scope.take(prefix.size) == prefix && scope.drop(prefix.size) in listOf(listOf("version"), listOf("dependencies", "dependency", "version"))
        }
    }
}
