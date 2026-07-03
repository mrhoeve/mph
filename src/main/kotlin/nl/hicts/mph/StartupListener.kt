package nl.hicts.mph

import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class StartupListener(private val environment: Environment) {

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        val port = environment.getProperty("local.server.port") ?: environment.getProperty("server.port") ?: "8080"
        val contextPath = environment.getProperty("server.servlet.context-path") ?: ""
        
        val url = "http://localhost:$port$contextPath"
        
        println("\n" + "*".repeat(60))
        println("MPH (Maven Project Helper) is ready!")
        println("Access the application at: $url")
        println("*".repeat(60) + "\n")
    }
}
