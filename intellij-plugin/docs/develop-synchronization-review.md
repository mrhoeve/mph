# Develop synchronization safety review

Scope: IntelliJ plugin, from selection and saved editor documents through Git preflight, develop update, stash, rebase, restoration, cancellation, reporting, and dependent version alignment. The standalone implementation was inspected for comparison and left unchanged. Intended behavior remains automatic resolution of committed version-only POM conflicts and prefix/dependent alignment after all selected repositories succeed.

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

## Recovery contract

Backups are intentionally retained after success, partial completion, and cancellation. Inspect Git status before restoring anything. A failed stash apply can leave a mixture of restored and unrestored paths; blindly applying it again can compound the conflict. The recovery instructions explain how to create a separate worktree at the original commit and apply the exact stash there, preserving the current workspace for comparison. POM snapshots cover the state immediately before alignment, including unselected dependents; they are not an automatic rollback.

## Remaining improvements before standalone retirement

1. **Detect external workspace changes.** Plugin mutations now share an application-wide lease. External Git clients and another IDE process cannot participate in that lease; verify branch/HEAD/model fingerprints before alignment to detect their changes. Repository-scoped leases could later allow independent workspaces to run concurrently.
2. **Make alignment transactional and previewable.** Durable POM copies make partial writes recoverable, but alignment is still a multi-file operation. Compute edits first, validate all targets, show changes to existing local versions, then apply as one logical operation with explicit recovery on a partial save.
3. **Separate Git execution, recovery state, and UI orchestration.** An injectable command runner and explicit workflow stages would support deterministic tests of disk-full errors, ref races, process-launch errors, and IDE lifecycle cancellation. Keep stdout and stderr separate for machine-readable Git output.
4. **Add backup browsing and cleanup.** Show retained runs with original branch, commit, stash, and POM copies, and offer a reviewed cleanup action. Do not delete backups merely because the Git phase succeeded.
5. **Use shared behavioral fixtures during migration.** Run equivalent standalone/plugin scenarios for module selection, prefix normalization, dependent updates, and conflict choices. Git plumbing differs, so parity should describe user outcomes rather than identical commands.

## Validation

Verified on Windows with JDK 21: the current full plugin suite passes all 124 tests, including the existing Git safety scenarios and new operation ownership, build prerequisite, and asynchronous Maven refresh regressions. `git diff --check` passes.

Real local Git repositories exercise committed version conflict resolution, uncommitted version conflicts, source conflicts, mixed conflicts, index/worktree separation, retained pre-existing stashes, recovery refs, ignored file collisions, active operation directories, checked-out develop worktrees, hidden index flags, narrow fetch mappings, deleted remote develop, merge topology, updateRefs configuration, cancellation at stash/restoration boundaries, concurrent starts, and exceptions after stashing. Pure tests cover strict XML conflict classification, line ending preservation, exact-byte alignment backups, dependent paths, and backup failure.

The dialog changes require an interactive IDE smoke test for saved/unsaved editor behavior, Stop/Close timing, copyable recovery details, and final alignment. Unit/integration tests do not simulate an IDE crash or arbitrary external processes modifying the workspace.
