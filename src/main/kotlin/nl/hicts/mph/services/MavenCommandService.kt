package nl.hicts.mph.services

import nl.hicts.mph.logging.LoggerDelegate
import org.springframework.stereotype.Service
import java.io.File
import java.util.concurrent.CompletableFuture

@Service
class MavenCommandService {
    private val logger by LoggerDelegate()

    fun runInstallInBackground(projectPom: File) {
        val projectDir = projectPom.parentFile
        val mvnw = findMvnw(projectDir) ?: "mvn"
        
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val command = if (mvnw.contains("mvnw")) {
            if (isWindows) listOf("cmd.exe", "/c", mvnw, "install", "-DskipTests")
            else listOf(mvnw, "install", "-DskipTests")
        } else {
            if (isWindows) listOf("cmd.exe", "/c", "mvn", "install", "-DskipTests")
            else listOf("mvn", "install", "-DskipTests")
        }

        CompletableFuture.runAsync {
            try {
                logger.info("Running command: ${command.joinToString(" ")} in ${projectDir.absolutePath}")
                val process = ProcessBuilder(command)
                    .directory(projectDir)
                    .redirectErrorStream(true)
                    .start()
                
                // Read output to avoid blocking
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        // Only log interesting lines or log all as debug
                        if (line.contains("[ERROR]") || line.contains("BUILD SUCCESS") || line.contains("BUILD FAILURE")) {
                            logger.info("[Maven] $line")
                        } else {
                            logger.debug("[Maven] $line")
                        }
                    }
                }
                
                val exitCode = process.waitFor()
                logger.info("Maven command finished with exit code $exitCode for ${projectPom.absolutePath}")
            } catch (e: Exception) {
                logger.error("Failed to run Maven command for ${projectPom.absolutePath}", e)
            }
        }
    }

    private fun findMvnw(dir: File): String? {
        var current: File? = dir
        // Try to find mvnw in the current directory or its parents (up to 5 levels)
        for (i in 0..5) {
            if (current == null) break
            val mvnwFileName = if (System.getProperty("os.name").lowercase().contains("win")) "mvnw.cmd" else "mvnw"
            val mvnw = File(current, mvnwFileName)
            if (mvnw.exists()) return mvnw.absolutePath
            current = current.parentFile
        }
        return null
    }
}
