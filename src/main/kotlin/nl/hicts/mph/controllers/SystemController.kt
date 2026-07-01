package nl.hicts.mph.controllers

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class SystemController {

    @Value("\${project.version:0.0.1-SNAPSHOT}")
    private lateinit var projectVersion: String

    @GetMapping("/api/system/info")
    fun getInfo(): SystemInfo {
        return SystemInfo(
            name = "Maven Project Helper",
            version = projectVersion
        )
    }
}

data class SystemInfo(
    val name: String,
    val version: String
)
