# Develop synchronization safety review

Scope: IntelliJ plugin only, from selection and saved editor documents through Git preflight, develop update, stash, rebase, restoration, cancellation, reporting, recovery cleanup, and dependent version alignment. Intended behavior remains automatic resolution of committed version-only POM conflicts and prefix/dependent alignment after all selected repositories succeed.

## Findings addressed

| Risk | Change |
| --- | --- |
| A stash version conflict could discard the local version, then delete the only convenient recovery copy. | No automatic stash-conflict resolution or stash deletion. Any unsuccessful apply stops alignment, including partial applies and untracked collisions. |
| Unconditional mixed reset erased the original staged/unstaged distinction. | Restore with `stash apply --index`; keep the original stash if restoring the index fails. |
| Rewritten commits were recoverable only through Git's temporary recovery mechanisms. | Create a named `refs/mph/recovery/<id>/head` ref before stashing/rebasing, plus durable instructions and the exact stash marker/object. |
| Stop killed Git during writes and replaced errors with a cancellation result that omitted stash information. | Finish the current command, stop at a boundary, retain recovery details, and suppress alignment after cancellation or dialog closure. |
| An exception after stashing could escape without repository-specific recovery information. | Catch at the repository boundary, attach recovery details, and continue processing other repositories. |
| Unsaved IDE documents were not included in Git's safety stash. | Save documents before starting; refuse to proceed if any remain unsaved. Skip alignment if new unsaved editor changes appear. |
| Final alignment could overwrite local version edits in selected or unselected dependent POMs. | Save byte-for-byte POM copies and a path manifest before any alignment writes. Refuse alignment when backup fails. |
| Fetch depended on configured remote mappings and could leave origin/develop stale. | Fetch an explicit develop refspec and pin the fetched commit ID for the operation. |
| Updating local develop could move a branch checked out elsewhere or overwrite a concurrent ref change. | Check linked worktrees and update with an expected old object ID. |
| Preflight missed rebase directories, sequencers, revert/bisect state, and hidden index changes. | Inspect Git operation paths and index flags; refuse unsupported submodule state. |
| Ignored files are omitted from the ordinary safety stash and could be overwritten by rebase checkout. | Reject overlaps with incoming and intermediate replayed trees, including file/directory collisions. |
| Git configuration could rewrite other branches, autosquash commits, or apply remembered resolutions. | Override updateRefs, autosquash, autostash and rerere for this operation. Retain merge topology and empty commits explicitly. |
| The version regex accepted multiple XML elements on one line; mixed conflict sets could be partially rewritten before failing. | Match plain version element text only, require the same element names and counts on both sides, validate every conflicting file before writing any, preserve line endings, and parse conflict paths with NUL delimiters. |
| Recovery hints were returned but omitted from the dialog. | Add selectable, copyable repository recovery details and alignment backup information. |
| One dialog could cancel another run, or a different MPH action could mutate files during synchronization. | Use per-run progress indicators and a shared lease across plugin mutations, held through post-rebase Maven refresh and alignment. |
| Alignment used Maven projects and coordinates captured before rebase. | Await Maven refresh and rediscover the workspace before selecting projects, taking POM snapshots, and applying versions. Skip on refresh/model failures, missing repositories, cancellation, or unsaved edits. |
| Recreating a merge can silently drop edits made directly in its merge commit. | Inspect replayed merges using Git's remerge diff. Leave manual merge changes and merges with more than two parents untouched for manual synchronization; ordinary two-parent merges retain topology. |
| Retaining a stash in the stash list alone does not protect older stashes from expiry or cleanup. | Pin the exact stash under `refs/mph/recovery/<id>/stash`, alongside the original `head` ref. Recovery remains possible after clearing stashes, expiring reflogs, and garbage collection. |
| Updating recovery instructions could truncate the only useful record, or a reporting failure could hide a completed stash. | Replace instructions atomically, discover/pin the stash even if its command throws, and include the known refs, marker, and exact object ID directly in the result. Stop before rebase if recording fails. |
| Branch changes or new edits after preflight could make later commands act on the wrong checkout. | Pin the original branch/HEAD, recheck checkout and Git operation state at boundaries, require a clean tree before rebase/restoration, recheck hidden index flags, and repeat ignored-file collision checks before rebase. |
| Configured fetch ref mappings and pruning could update unrelated local refs. | Fetch only the explicit remote develop ref with an empty refmap, no pruning, and no recursive submodule fetch. |
| A repository could change while a later selected repository was still synchronizing, before the alignment baseline was taken. | Capture each repository's fingerprint immediately upon its own successful completion and carry it through to alignment validation. |
| Conflict paths containing wildcard characters could stage unexpected files. | Use literal Git pathspecs when staging resolved POM conflicts. Disable rerere during stash restoration as well as rebase. |
| A later POM could change while an earlier POM was being saved. | Revalidate each document and its disk bytes immediately before its individual edit, retaining recovery copies if a partial save stops. |
| An alignment error could be presented as cancellation/no changes even after partial writes. | Propagate synchronization alignment errors to the dialog, showing the failure and recovery location instead of the ordinary cancellation message. |
| Git can retain `REBASE_HEAD` after a successful continuation, causing a false active-operation result and leaving work stashed. | Detect actual rebase directories and other active state; do not treat the informational `REBASE_HEAD` ref alone as an active rebase. |

## Recovery contract

Backups are intentionally retained after success, partial completion, and cancellation. Inspect Git status before restoring anything. A failed stash apply can leave a mixture of restored and unrestored paths; blindly applying it again can compound the conflict. The recovery instructions explain how to create a separate worktree at the original commit and apply the exact stash there, preserving the current workspace for comparison. POM snapshots cover the state immediately before alignment, including unselected dependents; they are not an automatic rollback.

## Completion follow-up

The [plugin completion review](plugin-completion-review.md) records the implemented external-change checks, reviewable alignment, Git execution/recovery separation, recovery browsing and cleanup, plugin-owned behavioral fixtures, and native IntelliJ conflict resolution. The deprecated application is not a plugin build or test dependency.

Multi-file saves remain recoverable rather than filesystem-atomic. The coordinator intentionally serializes plugin mutations across IDE projects; it does not lock other IDE processes or external Git clients. Live interaction, animation, and screen-reader checks remain on the documented release checklist.

Checks run at command boundaries and cannot prevent external writes during a Git command or arbitrary side effects from repository-defined hooks and filters. Stashes retain Git's stored representation of working files; exact-byte recovery is guaranteed by the separate POM snapshots for alignment. Process interruption is covered by retained refs and recovery records, but power-loss durability is not simulated by these tests.

## Validation

2026-10-30 review: all plugin production and test Kotlin sources compile with the installed Kotlin 2.4.10 compiler and JDK 21. Across a direct JUnit run and targeted reruns after correcting the stale `REBASE_HEAD` handling, 45 focused checks pass. The existing rejected-hook check fails in this sandbox because Windows denies Git's MSYS helper shell access, before the fixture hook can emit its expected message. `git diff --check` passes.

Full Gradle verification could not start because of cache-write and daemon-connection restrictions. The 14 selected IDE-platform checks failed during framework initialization with denied Windows registry access; their test bodies were not verified. Run `build-plugin-local.cmd` in the normal development environment to complete the full suite, packaging, and Plugin Verifier checks.

Real local Git repositories, with local bundles supplying remote refs, exercise committed version conflict resolution, uncommitted version conflicts, source conflicts, mixed conflicts, index/worktree separation, retained pre-existing stashes, recovery refs, ignored file collisions, active operation directories, checked-out develop worktrees, hidden index flags, narrow fetch mappings, missing remote develop, merge topology, updateRefs configuration, cancellation at stash/restoration boundaries, concurrent starts, and exceptions after stashing. Added regressions cover merge-only work, stash recovery after reflog expiry and garbage collection, recovery-write failures, branch and file changes between commands, multi-repository fingerprint timing, literal conflict paths, and retained `REBASE_HEAD`. Pure tests cover strict XML conflict classification, line ending preservation, exact-byte alignment backups, dependent paths, backup failure, and recovery catalog visibility after interrupted writes. IDE tests additionally cover changes during a preceding POM save and accurate reporting of alignment errors.

The dialog changes require an interactive IDE smoke test for saved/unsaved editor behavior, Stop/Close timing, copyable recovery details, and final alignment. Unit/integration tests do not simulate an IDE crash or arbitrary external processes modifying the workspace.
