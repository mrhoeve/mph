package nl.hicts.mph.services

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

class MavenSettingsLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `should use the configured local repository and Nexus mirror credentials from the environment`() {
        val username = "test-user"
        val password = "test-password"
        val expectedAuthorization = "Basic " + Base64.getEncoder()
            .encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
        val pom = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.example</groupId>
              <artifactId>environment-bom</artifactId>
              <version>1.0.0</version>
              <packaging>pom</packaging>
            </project>
        """.trimIndent()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            handleRepositoryRequest(exchange, expectedAuthorization, pom)
        }
        server.start()

        try {
            val localRepository = tempDir.resolve("custom-repository")
            val settingsFile = tempDir.resolve("settings.xml")
            Files.writeString(settingsFile, """
                <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
                  <localRepository>${'$'}{env.MPH_TEST_LOCAL_REPOSITORY}</localRepository>
                  <mirrors>
                    <mirror>
                      <id>test-nexus</id>
                      <url>${'$'}{env.MPH_TEST_NEXUS_URL}</url>
                      <mirrorOf>central</mirrorOf>
                    </mirror>
                  </mirrors>
                  <servers>
                    <server>
                      <id>test-nexus</id>
                      <username>${'$'}{env.MPH_TEST_NEXUS_USERNAME}</username>
                      <password>${'$'}{env.MPH_TEST_NEXUS_PASSWORD}</password>
                    </server>
                  </servers>
                </settings>
            """.trimIndent())
            val properties = Properties().also {
                it.setProperty("user.home", tempDir.toString())
                it.setProperty("maven.user.settings", settingsFile.toString())
            }
            val environment = mapOf(
                "MPH_TEST_LOCAL_REPOSITORY" to localRepository.toString(),
                "MPH_TEST_NEXUS_URL" to "http://127.0.0.1:${server.address.port}/repository/",
                "MPH_TEST_NEXUS_USERNAME" to username,
                "MPH_TEST_NEXUS_PASSWORD" to password
            )
            val settingsLoader = MavenSettingsLoader(
                systemProperties = properties,
                environment = environment,
                userSettingsFile = settingsFile.toFile(),
                globalSettingsFile = null
            )

            val model = MavenModelResolver(settingsLoader = settingsLoader)
                .resolveModel("org.example", "environment-bom", "1.0.0")

            assertEquals("environment-bom", model.artifactId)
            assertTrue(
                Files.isRegularFile(localRepository.resolve("org/example/environment-bom/1.0.0/environment-bom-1.0.0.pom"))
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `system property should override the settings local repository`() {
        val settingsRepository = tempDir.resolve("settings-repository")
        val overriddenRepository = tempDir.resolve("overridden-repository")
        val settingsFile = tempDir.resolve("settings.xml")
        Files.writeString(settingsFile, """
            <settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
              <localRepository>$settingsRepository</localRepository>
            </settings>
        """.trimIndent())
        val properties = Properties().also {
            it.setProperty("user.home", tempDir.toString())
            it.setProperty("maven.repo.local", overriddenRepository.toString())
        }

        val configuration = MavenSettingsLoader(
            systemProperties = properties,
            environment = emptyMap(),
            userSettingsFile = settingsFile.toFile(),
            globalSettingsFile = null
        ).load()

        assertEquals(overriddenRepository.toFile().absoluteFile.normalize(), configuration.localRepository)
    }

    private fun handleRepositoryRequest(exchange: HttpExchange, expectedAuthorization: String, pom: String) {
        if (exchange.requestHeaders.getFirst("Authorization") != expectedAuthorization) {
            exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"test-nexus\"")
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
            return
        }

        val pomBytes = pom.toByteArray(StandardCharsets.UTF_8)
        val body = when {
            exchange.requestURI.path.endsWith("environment-bom-1.0.0.pom") -> pomBytes
            exchange.requestURI.path.endsWith("environment-bom-1.0.0.pom.sha1") -> {
                MessageDigest.getInstance("SHA-1").digest(pomBytes)
                    .joinToString("") { "%02x".format(it) }
                    .toByteArray(StandardCharsets.UTF_8)
            }
            else -> null
        }
        if (body != null) {
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        } else {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
    }
}
