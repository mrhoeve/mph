package nl.hicts.mph.services

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

class MavenCommandLoggingTest {

    @TempDir
    lateinit var tempDir: Path

    private val service = MavenCommandService()

    @Test
    fun `should mask sensitive arguments in logs`() {
        val projectDir = Files.createDirectories(tempDir.resolve("project"))
        createDummyMvnw(projectDir)
        
        val logger = LoggerFactory.getLogger(MavenCommandService::class.java) as Logger
        val listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
        logger.addAppender(listAppender)

        try {
            val args = listOf("-Dclm.username=secret-user", "-Dclm.password=secret-pass", "verify")
            val mask = listOf("clm.username", "clm.password")
            
            service.runMavenCommandInBackground(projectDir.toFile(), args, "test-masking", mask)
                .get(10, TimeUnit.SECONDS)

            val logEvents = listAppender.list
            val commandLog = logEvents.find { it.message.contains("Running test-masking:") }
            
            assertTrue(commandLog != null, "Log message not found")
            val logMessage = commandLog!!.formattedMessage
            
            assertTrue(logMessage.contains("-Dclm.username=***"), "Username not masked: $logMessage")
            assertTrue(logMessage.contains("-Dclm.password=***"), "Password not masked: $logMessage")
            assertTrue(!logMessage.contains("secret-user"), "Sensitive username leaked: $logMessage")
            assertTrue(!logMessage.contains("secret-pass"), "Sensitive password leaked: $logMessage")
            assertTrue(logMessage.contains("verify"), "Non-sensitive argument missing: $logMessage")
        } finally {
            logger.detachAppender(listAppender)
        }
    }

    private fun createDummyMvnw(directory: Path) {
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val wrapper = directory.resolve(if (windows) "mvnw.cmd" else "mvnw")
        val content = if (windows) "@echo off\r\nexit /b 0\r\n" else "#!/bin/sh\nexit 0\n"
        Files.writeString(wrapper, content)
        wrapper.toFile().setExecutable(true)
    }
}
