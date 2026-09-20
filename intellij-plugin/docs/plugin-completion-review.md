# Plugin completion review

The plugin owns its implementation and fixtures. The deprecated standalone application is not a dependency and can be removed independently.

## Alignment review

Bulk alignment, explicit version updates, realignment, and post-rebase alignment prepare a file-by-file diff before applying. Cancelling the review does not write POMs. Every input document and its disk bytes are checked again before application; unavailable/read-only targets and stale previews stop the operation before writes. Generated POMs must parse as XML.

Recovery copies preserve both exact disk bytes and original editor text in the IDE configuration directory under `mph-recovery`. A multi-file save is not filesystem-atomic: a failure names all possibly changed files and retains copies for inspection or Undo rather than silently claiming success or overwriting a concurrent edit during rollback.

Validation: focused bulk-alignment and tool-window/rebase-dialog tests pass, including stale editor/disk previews and a failure on the second save.

## Follow-up work in this change series

- Detect external branch/HEAD/worktree changes before post-rebase alignment.
- Isolate Git command execution and recovery state for fault injection.
- Browse recovery runs and explicitly review cleanup.
- Keep migration behavior fixtures entirely within the plugin.
- Organize toolbar/context actions.
- Verify theme, accessibility, layout, and operation lifecycle behavior; record live-IDE limitations separately.
