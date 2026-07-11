package nl.hicts.mph.controllers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI

class SystemControllerTest {

    @Test
    fun `should show development for an exploded classpath`() {
        assertEquals(
            "Development",
            displayVersion("1.2.3", URI.create("file:/workspace/target/classes/SystemController.class").toURL())
        )
    }

    @Test
    fun `should show release version for a packaged jar`() {
        assertEquals(
            "1.2.3",
            displayVersion("1.2.3", URI.create("jar:file:/app/mph.jar!/BOOT-INF/classes!/SystemController.class").toURL())
        )
    }
}
