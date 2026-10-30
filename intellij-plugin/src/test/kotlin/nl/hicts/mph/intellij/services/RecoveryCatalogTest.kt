package nl.hicts.mph.intellij.services

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class RecoveryCatalogTest {
    @Test fun `atomic instruction updates and orphaned temporary writes remain discoverable`() {
        val root = Files.createTempDirectory("mph-recovery-record-")
        try {
            val run = Files.createDirectory(root.resolve(UUID.randomUUID().toString()))
            val record = run.resolve("recovery.txt")
            writeGitRecoveryRecord(record, "Original commits and stash marker")
            val orphan = Files.createTempFile(root, "recovery-", ".tmp")
            Files.writeString(orphan, "Interrupted replacement")
            assertTrue(RecoveryCatalog(listOf(root)).list().single().details.contains("Original commits"))
            writeGitRecoveryRecord(record, "Original commits and exact stash object")
            val reviewed = RecoveryCatalog(listOf(root)).list().single()
            assertTrue(reviewed.details.contains("exact stash object"))
            assertEquals(setOf("recovery.txt"), reviewed.files.keys)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `cleanup only deletes the reviewed unchanged run`() {
        val root = Files.createTempDirectory("mph-recovery-catalog-")
        try {
            val run = Files.createDirectory(root.resolve("alignment-${UUID.randomUUID()}"))
            Files.writeString(run.resolve("0.pom"), "<project/>")
            Files.writeString(run.resolve("original-paths.properties"), "0.pom=example/pom.xml")
            val catalog = RecoveryCatalog(listOf(root))
            val reviewed = catalog.list().single()
            Files.writeString(run.resolve("0.pom"), "later recovery data")
            assertThrows(IllegalStateException::class.java) { catalog.deleteReviewed(reviewed) }
            assertTrue(Files.exists(run))
            val unowned = Files.createDirectory(root.resolve("personal-files"))
            Files.writeString(unowned.resolve("notes.txt"), "keep")
            catalog.deleteReviewed(catalog.list().single())
            assertFalse(Files.exists(run))
            assertEquals("keep", Files.readString(unowned.resolve("notes.txt")))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun `unknown files and paths outside the catalog are never deleted`() {
        val root = Files.createTempDirectory("mph-recovery-catalog-")
        try {
            val run = Files.createDirectory(root.resolve("alignment-${UUID.randomUUID()}"))
            Files.writeString(run.resolve("personal.txt"), "keep")
            val catalog = RecoveryCatalog(listOf(root))
            assertTrue(catalog.list().isEmpty())
            assertThrows(IllegalStateException::class.java) { catalog.deleteReviewed(RecoveryRun(root, emptyMap(), "")) }
            assertTrue(Files.exists(run.resolve("personal.txt")))
        } finally { root.toFile().deleteRecursively() }
    }
}
