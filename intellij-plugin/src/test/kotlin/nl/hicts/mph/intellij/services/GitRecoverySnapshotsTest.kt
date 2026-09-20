package nl.hicts.mph.intellij.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files
import java.util.Properties

class GitRecoverySnapshotsTest {
    @Test
    fun `alignment snapshots preserve exact bytes and paths including unrelated dependents`() {
        val root = Files.createTempDirectory("mph-alignment-recovery-")
        try {
            val gitDirectory = Files.createDirectory(root.resolve(".git"))
            val selected = root.resolve("selected-pom.xml")
            val dependent = Files.createDirectories(root.resolve("other repository")).resolve("pom.xml")
            val original = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<project>\r\n</project>\r\n".toByteArray()
            Files.write(selected, original)
            Files.writeString(dependent, "local dependent version edit")
            val manifestPath = GitRecoverySnapshots.capture(gitDirectory, listOf(selected, dependent, selected))
            val manifest = Properties().apply { Files.newInputStream(manifestPath).use(::load) }
            assertEquals(2, manifest.size)
            assertEquals(selected.toString(), manifest.getProperty("0.pom"))
            assertEquals(dependent.toString(), manifest.getProperty("1.pom"))
            Files.writeString(selected, "aligned")
            assertArrayEquals(original, Files.readAllBytes(manifestPath.parent.resolve("0.pom")))
            assertEquals("local dependent version edit", Files.readString(manifestPath.parent.resolve("1.pom")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing POM prevents alignment instead of claiming a complete backup`() {
        val root = Files.createTempDirectory("mph-alignment-missing-")
        try {
            assertThrows(IllegalArgumentException::class.java) {
                GitRecoverySnapshots.capture(root, listOf(root.resolve("missing.xml")))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
