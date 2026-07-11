package nl.hicts.mph

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.env.Environment
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

class StartupListenerTest {

    @Test
    fun `should print runtime port and context path`() {
        val environment = mockk<Environment>()
        every { environment.getProperty("local.server.port") } returns "9090"
        every { environment.getProperty("server.port") } returns "8080"
        every { environment.getProperty("server.servlet.context-path") } returns "/mph"

        val output = captureStandardOutput { StartupListener(environment).onApplicationReady() }

        assertEquals(true, output.contains("MPH (Maven Project Helper) is ready!"))
        assertEquals(true, output.contains("Access the application at: http://localhost:9090/mph"))
    }

    @Test
    fun `should use configured server port when runtime port is unavailable`() {
        val environment = mockk<Environment>()
        every { environment.getProperty("local.server.port") } returns null
        every { environment.getProperty("server.port") } returns "8181"
        every { environment.getProperty("server.servlet.context-path") } returns null

        val output = captureStandardOutput { StartupListener(environment).onApplicationReady() }

        assertEquals(true, output.contains("http://localhost:8181"))
    }

    @Test
    fun `should use conventional defaults when server properties are unavailable`() {
        val environment = mockk<Environment>()
        every { environment.getProperty(any()) } returns null

        val output = captureStandardOutput { StartupListener(environment).onApplicationReady() }

        assertEquals(true, output.contains("http://localhost:8080"))
    }

    private fun captureStandardOutput(action: () -> Unit): String {
        val original = System.out
        val bytes = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(bytes, true, StandardCharsets.UTF_8))
            action()
        } finally {
            System.setOut(original)
        }
        return bytes.toString(StandardCharsets.UTF_8)
    }
}
