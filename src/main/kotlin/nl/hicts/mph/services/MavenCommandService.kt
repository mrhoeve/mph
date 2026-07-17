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

    fun runMavenCommandInBackground(
        projectDir: File,
        args: List<String>,
        label: String = "command",
        maskArguments: List<String> = emptyList()
    ): CompletableFuture<Int> {
        val mvnw = findMvnw(projectDir) ?: "mvn"
        val command = buildBaseCommand(mvnw) + args

        val future = CompletableFuture<Int>()
        CompletableFuture.runAsync {
            try {
                val maskedCommand = command.map { maskArgument(it, maskArguments) }
                logger.info("Running $label: ${maskedCommand.joinToString(" ")} in ${projectDir.absolutePath}")
                val process = ProcessBuilder(command)
                    .directory(projectDir)
                    .redirectErrorStream(true)
                    .start()

                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line -> logOutputLine(label, line) }
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

    private fun buildBaseCommand(mvnw: String): List<String> {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val executable = if (mvnw.contains("mvnw")) mvnw else "mvn"
        return if (isWindows) listOf("cmd.exe", "/c", executable) else listOf(executable)
    }

    private fun maskArgument(argument: String, masks: List<String>): String {
        val shouldMask = masks.any { argument.startsWith("-D$it=") || argument.startsWith("$it=") }
        if (!shouldMask) return argument
        return "${argument.substringBefore('=')}=***"
    }

    private fun logOutputLine(label: String, line: String) {
        val isImportant = listOf("[ERROR]", "BUILD SUCCESS", "BUILD FAILURE").any(line::contains)
        if (isImportant) logger.info("[Maven $label] $line") else logger.debug("[Maven $label] $line")
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
