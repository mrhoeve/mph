package nl.hicts.mph.intellij.services

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private const val REV_PARSE = "rev-parse"

/** A conservative check between Git completion, Maven import, and applying reviewed edits. */
internal data class GitWorkspaceFingerprint(val root: Path, val digest: String) {
    fun verify() {
        check(capture(root) == this) {
            "The repository changed outside this synchronization: $root. Alignment was skipped; refresh and review it again."
        }
    }

    companion object {
        fun capture(root: Path): GitWorkspaceFingerprint {
            val runner = NativeGitCommandRunner()
            val digest = MessageDigest.getInstance("SHA-256")
            fun add(bytes: ByteArray) {
                digest.update(java.nio.ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
                digest.update(bytes)
            }
            fun git(vararg args: String): String {
                val result = runner.execute(root, listOf("--no-optional-locks") + args, null, emptyMap())
                check(result.exitCode == 0) { "Cannot verify repository state: ${result.diagnostic}" }
                return result.output.also { add(it.toByteArray(Charsets.UTF_8)) }
            }
            git(REV_PARSE, "HEAD")
            git(REV_PARSE, "--symbolic-full-name", "HEAD")
            git("status", "--porcelain=v1", "--untracked-files=all", "-z")
            git("diff", "--no-ext-diff", "--no-textconv", "--binary")
            git("diff", "--cached", "--no-ext-diff", "--no-textconv", "--binary")
            val paths = git("ls-files", "--cached", "--others", "--exclude-standard", "-z")
            paths.split('\u0000').filter { it.substringAfterLast('/') == "pom.xml" }.distinct().sorted().forEach { relative ->
                val file = root.toAbsolutePath().normalize().resolve(relative).normalize()
                check(file.startsWith(root.toAbsolutePath().normalize()) && !Files.isSymbolicLink(file)) { "Unsafe POM path: $file" }
                add(if (Files.exists(file)) Files.readAllBytes(file) else byteArrayOf())
            }
            listOf("rebase-merge", "rebase-apply", "sequencer", "MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "BISECT_LOG", "index.lock").forEach {
                val path = Path.of(git(REV_PARSE, "--path-format=absolute", "--git-path", it).trim())
                check(!Files.exists(path)) { "Another Git operation is in progress: $root" }
            }
            return GitWorkspaceFingerprint(root.toAbsolutePath().normalize(), digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
