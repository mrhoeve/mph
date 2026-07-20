package nl.hicts.mph.intellij.services

import com.intellij.util.io.HttpRequests

object SpringBootVersions {
    fun stableNewerThan(current: String, versions: Collection<String>): List<String> = versions
        .map { it.removePrefix("v") }
        .filter { version -> version.matches(Regex("\\d+(?:\\.\\d+)+")) }
        .distinct()
        .filter { compare(it, current) > 0 }
        .sortedWith(::compare)
        .reversed()

    internal fun compare(first: String, second: String): Int {
        val left = first.split('.').mapNotNull(String::toIntOrNull)
        val right = second.removeSuffix("-SNAPSHOT").split('.').mapNotNull(String::toIntOrNull)
        return (0 until maxOf(left.size, right.size)).firstNotNullOfOrNull { index ->
            (left.getOrElse(index) { 0 } - right.getOrElse(index) { 0 }).takeIf { it != 0 }
        } ?: 0
    }
}

object SpringBootVersionCatalog {
    private const val TAGS_URL = "https://api.github.com/repos/spring-projects/spring-boot/tags?per_page=100"

    fun fetchStableVersions(): List<String> {
        val json = HttpRequests.request(TAGS_URL)
            .userAgent("Maven-Project-Helper-IntelliJ-Plugin")
            .readString()
        return Regex("\\\"name\\\"\\s*:\\s*\\\"v?(\\d+(?:\\.\\d+)+)\\\"")
            .findAll(json)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }
}
