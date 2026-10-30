package nl.hicts.mph.intellij.services

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

private const val REV_PARSE = "rev-parse"

/** A conservative check between Git completion, Maven import, and applying reviewed edits. */
data class GitWorkspaceFingerprint(val root: Path, val digest: String, private val allowRebase: Boolean = false) {
    fun verify() {
        check(capture(root, allowRebase) == this) {
            "The repository changed outside this synchronization: $root. Alignment was skipped; refresh and review it again."
        }
    }

    companion object {
        fun capture(root: Path, allowRebase: Boolean = false): GitWorkspaceFingerprint {
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
            git("ls-files", "--stage", "-v", "-z")
            val paths = git("ls-files", "--cached", "--others", "--exclude-standard", "-z")
            paths.split('\u0000').filter(String::isNotEmpty).distinct().sorted().forEach { relative ->
                val file = root.toAbsolutePath().normalize().resolve(relative).normalize()
                check(file.startsWith(root.toAbsolutePath().normalize())) { "Unsafe workspace path: $file" }
                // Include untracked content and files hidden from diff by index flags. Never follow symlinks.
                var parent = file.parent
                while (parent != root.toAbsolutePath().normalize()) {
                    check(parent != null && !Files.isSymbolicLink(parent)) { "Unsafe workspace path: $file" }
                    parent = parent.parent
                }
                when {
                    Files.isSymbolicLink(file) -> add(("link:" + Files.readSymbolicLink(file)).toByteArray(Charsets.UTF_8))
                    Files.isRegularFile(file) -> {
                        val contentHash = MessageDigest.getInstance("SHA-256")
                        Files.newInputStream(file).use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                contentHash.update(buffer, 0, count)
                            }
                        }
                        add(contentHash.digest())
                        add(byteArrayOf(if (Files.isExecutable(file)) 1 else 0))
                    }
                    Files.isDirectory(file) -> add("directory".toByteArray())
                    else -> add("missing".toByteArray())
                }
            }
            val gitDirectory = Path.of(git(REV_PARSE, "--absolute-git-dir").trim())
            listOf("rebase-merge", "rebase-apply", "sequencer", "MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "BISECT_LOG", "index.lock").forEach {
                val path = gitDirectory.resolve(it)
                if (allowRebase && it == "rebase-merge") {
                    check(Files.isDirectory(path)) { "The rebase is no longer active: $root" }
                    listOf("head-name", "orig-head", "onto", "stopped-sha", "done", "git-rebase-todo").forEach { name ->
                        val state = path.resolve(name)
                        add(if (Files.exists(state)) Files.readAllBytes(state) else byteArrayOf())
                    }
                } else check(!Files.exists(path)) { "Another Git operation is in progress: $root" }
            }
            return GitWorkspaceFingerprint(root.toAbsolutePath().normalize(), digest.digest().joinToString("") { "%02x".format(it) }, allowRebase)
        }
    }
}

/** Unselected dependent projects must not acquire a new baseline after synchronization. */
internal class AlignmentWorkspaceBaseline private constructor(
    private val repositories: List<GitWorkspaceFingerprint>,
    private val poms: Map<Path, String>,
) {
    fun verify() {
        repositories.forEach(GitWorkspaceFingerprint::verify)
        check(poms.all { (path, hash) -> pomHash(path) == hash }) {
            "A dependent POM changed during synchronization. Alignment was skipped; review it again."
        }
    }

    companion object {
        fun capture(plan: GitRebasePlan): AlignmentWorkspaceBaseline {
            val selected = plan.repositories.map { Path.of(it.rootPath).toAbsolutePath().normalize() }.toSet()
            val dependents = plan.workspaceProjects.filter { project ->
                project.gitRootPath?.let { Path.of(it).toAbsolutePath().normalize() } !in selected
            }
            val repositories = dependents.mapNotNull { it.gitRootPath }.map { Path.of(it).toAbsolutePath().normalize() }
                .distinct().map { GitWorkspaceFingerprint.capture(it) }
            val poms = dependents.map { Path.of(it.pomPath).toAbsolutePath().normalize() }.distinct().associateWith(::pomHash)
            return AlignmentWorkspaceBaseline(repositories, poms)
        }

        private fun pomHash(path: Path): String {
            check(path.toRealPath() == path && Files.isRegularFile(path)) { "Unsafe dependent POM path: $path" }
            return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        }
    }
}
