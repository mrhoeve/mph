package nl.hicts.mph.services

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

class SpringBootVersionServiceTest {

    @Test
    fun testSortingAndNewerLogic() {
        val webClientBuilder = mockk<WebClient.Builder>()
        val webClient = mockk<WebClient>()
        val requestHeadersUriSpec = mockk<WebClient.RequestHeadersUriSpec<*>>()
        val requestHeadersSpec = mockk<WebClient.RequestHeadersSpec<*>>()
        val responseSpec = mockk<WebClient.ResponseSpec>()

        every { webClientBuilder.baseUrl("https://repo1.maven.org") } returns webClientBuilder
        every { webClientBuilder.defaultHeader(any(), any()) } returns webClientBuilder
        every { webClientBuilder.build() } returns webClient
        every { webClient.get() } returns requestHeadersUriSpec
        every { requestHeadersUriSpec.uri("/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml") } returns requestHeadersSpec
        every { requestHeadersSpec.retrieve() } returns responseSpec

        val xml = """
            <metadata>
              <versioning>
                <versions>
                  <version>3.4.0</version>
                  <version>3.4.1</version>
                  <version>3.5.0</version>
                  <version>3.5.16</version>
                  <version>4.0.0</version>
                  <version>4.1.0</version>
                  <version>3.5.14</version>
                </versions>
              </versioning>
            </metadata>
        """.trimIndent()

        every { responseSpec.bodyToMono(String::class.java) } returns Mono.just(xml)

        val service = SpringBootVersionService(webClientBuilder)

        // Test 1: Current is 3.5.14, should suggest 3.5.16 and 4.1.0
        val suggestions = service.getSuggestions("3.5.14").block()!!

        assertEquals("3.5.14", suggestions.currentVersion)
        assertEquals("3.5.16", suggestions.latestInSeries)
        assertEquals("4.1.0", suggestions.latestOverall)

        // Test 2: Current is 4.1.0, should suggest nothing
        val suggestions2 = service.getSuggestions("4.1.0").block()!!
        assertNull(suggestions2.latestInSeries)
        assertNull(suggestions2.latestOverall)
    }
}
