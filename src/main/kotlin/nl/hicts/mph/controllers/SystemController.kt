package nl.hicts.mph.controllers

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URL

@RestController
class SystemController {

    @Value("\${project.version:0.0.1-SNAPSHOT}")
    private lateinit var projectVersion: String

    @GetMapping("/api/system/info")
    fun getInfo(): SystemInfo {
        val classResource = SystemController::class.java.getResource("SystemController.class")
        return SystemInfo(
            name = "Maven Project Helper",
            version = displayVersion(projectVersion, classResource)
        )
    }
}

internal fun displayVersion(projectVersion: String, classResource: URL?): String {
    val location = classResource?.toExternalForm().orEmpty()
    val isPackagedJar = classResource?.protocol == "jar" || location.contains("!/")
    return if (isPackagedJar) projectVersion else "Development"
}

data class SystemInfo(
    val name: String,
    val version: String
)
