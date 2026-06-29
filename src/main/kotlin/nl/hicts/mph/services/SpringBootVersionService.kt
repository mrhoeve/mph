package nl.hicts.mph.services

import nl.hicts.mph.logging.LoggerDelegate
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.util.regex.Pattern

@Service
class SpringBootVersionService(
    webClientBuilder: WebClient.Builder
) {
    private val log by LoggerDelegate()
    private val webClient = webClientBuilder
        .baseUrl("https://repo1.maven.org")
        .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
        .build()

    fun fetchVersions(): Mono<List<String>> {
        return webClient.get()
            .uri("/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml")
            .retrieve()
            .bodyToMono(String::class.java)
            .map { xml ->
                val versions = mutableListOf<String>()
                val matcher = Pattern.compile("<version>(.*?)</version>").matcher(xml)
                while (matcher.find()) {
                    versions.add(matcher.group(1).trim())
                }
                log.info("Found ${versions.size} versions in Maven Central")
                versions as List<String>
            }
            .doOnSubscribe { log.info("Fetching Spring Boot versions from Maven Central...") }
            .onErrorResume { e ->
                log.error("Error fetching Spring Boot versions: ${e.message}", e)
                Mono.just(emptyList())
            }
    }

    fun getSuggestions(currentVersion: String): Mono<SpringBootUpgradeSuggestions> {
        return fetchVersions().map { allVersions ->
            val stableVersions = allVersions
                .filter { isStable(it) }
                .sortedWith(VersionComparator)

            if (stableVersions.isEmpty()) {
                return@map SpringBootUpgradeSuggestions(currentVersion, null, null)
            }

            val latestOverall = stableVersions.last()
            
            val parts = currentVersion.split(".")
            val latestInSeries = if (parts.size >= 2) {
                val series = "${parts[0]}.${parts[1]}."
                stableVersions.filter { it.startsWith(series) }.lastOrNull()
            } else {
                null
            }

            SpringBootUpgradeSuggestions(
                currentVersion = currentVersion,
                latestInSeries = if (latestInSeries != null && isNewer(latestInSeries, currentVersion)) latestInSeries else null,
                latestOverall = if (isNewer(latestOverall, currentVersion) && latestOverall != latestInSeries) latestOverall else null
            )
        }
    }

    private fun isNewer(v1: String, v2: String): Boolean {
        return VersionComparator.compare(v1, v2) > 0
    }

    private fun isStable(version: String): Boolean {
        val lowercase = version.lowercase()
        return !lowercase.contains("m") && 
               !lowercase.contains("rc") && 
               !lowercase.contains("snapshot") && 
               !lowercase.contains("milestone")
    }
}

object VersionComparator : Comparator<String> {
    override fun compare(v1: String, v2: String): Int {
        val p1 = v1.split(".").mapNotNull { it.toIntOrNull() }
        val p2 = v2.split(".").mapNotNull { it.toIntOrNull() }
        
        val size = maxOf(p1.size, p2.size)
        for (i in 0 until size) {
            val val1 = p1.getOrNull(i) ?: 0
            val val2 = p2.getOrNull(i) ?: 0
            if (val1 != val2) {
                return val1.compareTo(val2)
            }
        }
        return 0
    }
}

data class SpringBootUpgradeSuggestions(
    val currentVersion: String,
    val latestInSeries: String?,
    val latestOverall: String?
)
