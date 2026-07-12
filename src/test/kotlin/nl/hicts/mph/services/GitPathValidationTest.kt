package nl.hicts.mph.services

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class GitPathValidationTest {
    private val gitService = mockk<GitService>()
    private val service = MavenProjectService(
        mockk(),
        gitService,
        mockk(),
        mockk(),
        mockk()
    )

    @Test
    fun `latest tag rejects a path outside discovered workspace projects`() {
        val basePath = Paths.get("src/test/resources/test-data").toAbsolutePath().normalize()
        val outsidePath = basePath.parent.resolve("outside/pom.xml").toString()

        val exception = assertThrows(IllegalArgumentException::class.java) {
            service.getLatestTag(basePath, 3, outsidePath)
        }

        assertEquals("Project was not found in the configured workspace: $outsidePath", exception.message)
        verify(exactly = 0) { gitService.getLatestTagInfo(any()) }
    }

    @Test
    fun `latest tag passes the server-discovered pom file to Git`() {
        val basePath = Paths.get("src/test/resources/test-data").toAbsolutePath().normalize()
        val pomPath = basePath.resolve("a-project/pom.xml").toFile().absolutePath
        val expected = TagInfo("1.0.0", "v1.0.0")
        every { gitService.getLatestTagInfo(match { it.absolutePath == pomPath }) } returns expected

        val actual = service.getLatestTag(basePath, 3, pomPath)

        assertEquals(expected, actual)
        verify(exactly = 1) { gitService.getLatestTagInfo(match { it.absolutePath == pomPath }) }
    }
}
