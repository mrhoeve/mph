package nl.hicts.mph.services

import nl.hicts.mph.logging.LoggerDelegate
import org.springframework.stereotype.Service
import java.io.File
import java.util.concurrent.CompletableFuture

@Service
class MavenCommandService {
    private val logger by LoggerDelegate()

    fun runInstallInBackground(projectPom: File) {
        runMavenCommandInBackground(projectPom.parentFile, listOf("install", "-DskipTests"), "install")
    }

    fun runMavenCommandInBackground(projectDir: File, args: List<String>, label: String = "command"): CompletableFuture<Int> {
        val mvnw = findMvnw(projectDir) ?: "mvn"
        
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val baseCommand = if (mvnw.contains("mvnw")) {
            if (isWindows) listOf("cmd.exe", "/c", mvnw)
            else listOf(mvnw)
        } else {
            if (isWindows) listOf("cmd.exe", "/c", "mvn")
            else listOf("mvn")
        }
        
        val command = baseCommand + args

        val future = CompletableFuture<Int>()
        CompletableFuture.runAsync {
            try {
                logger.info("Running $label: ${command.joinToString(" ")} in ${projectDir.absolutePath}")
                val process = ProcessBuilder(command)
                    .directory(projectDir)
                    .redirectErrorStream(true)
                    .start()
                
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        if (line.contains("[ERROR]") || line.contains("BUILD SUCCESS") || line.contains("BUILD FAILURE")) {
                            logger.info("[Maven $label] $line")
                        } else {
                            logger.debug("[Maven $label] $line")
                        }
                    }
                }
                
                val exitCode = process.waitFor()
                logger.info("Maven $label finished with exit code $exitCode for ${projectDir.absolutePath}")
                future.complete(exitCode)
            } catch (e: Exception) {
                logger.error("Failed to run Maven $label for ${projectDir.absolutePath}", e)
                future.completeExceptionally(e)
            }
        }
        return future
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
