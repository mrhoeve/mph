# Plugin completion review

The plugin owns its implementation and fixtures. The deprecated standalone application is not a dependency and can be removed independently.

## Alignment review

Bulk alignment, explicit version updates, realignment, and post-rebase alignment prepare a file-by-file diff before applying. Cancelling the review does not write POMs. Every input document and its disk bytes are checked again before application; unavailable/read-only targets and stale previews stop the operation before writes. Generated POMs must parse as XML.

Recovery copies preserve both exact disk bytes and original editor text in the IDE configuration directory under `mph-recovery`. A multi-file save is not filesystem-atomic: a failure names all possibly changed files and retains copies for inspection or Undo rather than silently claiming success or overwriting a concurrent edit during rollback.

Validation: all 139 plugin tests pass on Windows, including stale editor/disk previews, a failure on the second save, real Git recovery failures, external-change detection, and dialog lifecycle checks. The plugin package builds successfully. Offline Plugin Verifier checks report compatibility with IntelliJ IDEA 2026.1.4 and 2026.2.2. Both report four experimental-API usages in the existing `MavenSyncSpec` refresh adapter; recheck that isolated adapter when upgrading IDE support. The online verifier stalled on Marketplace requests, so the completed check used the cached IDEs and dependencies.

## Completed implementation

- **External changes:** branch, HEAD, staged/unstaged diffs, tracked/untracked POM bytes, active Git operations, and the refreshed Maven model are checked before post-rebase alignment and again after review. This detects concurrent edits; it cannot make multiple filesystem writes atomic against an unrelated process.
- **Git separation:** the service delegates to a workflow with an injectable command runner and recovery writer. stdout and stderr remain separate. Ref-update and recovery-write failures have deterministic regression tests.
- **Recovery browser:** the toolbar lists recovery instructions and original POM locations. Cleanup requires explicit review, rejects changed or unrecognized files and linked paths, and deletes only that run's copy/instruction files. Git refs and stashes remain available.
- **Plugin-owned contracts:** fixtures and expected outcomes live in the plugin. No test imports standalone classes/resources. The plugin owns `pluginVersion` and builds without the root application POM; the existing combined release workflow updates the plugin-owned version.
- **Toolbar and context actions:** commands are grouped by inspection, versions, Git/build, settings, and view controls. Context actions preserve multiple selection and support Shift+F10 and the context-menu key.
- **Native conflict resolution:** a stopped conflict row offers **Resolve Conflicts…**, opening IntelliJ's native merge UI with rebase-aware sides. Finishing a merge does not implicitly continue a rebase, reapply a partially restored stash, or align versions. Instructions explain the appropriate next steps. Committed version-only conflicts still resolve automatically.

## UI validation

The test runtime loads MPH and its declared plugin dependencies, avoiding unrelated bundled language services. Automated platform tests exercise dialog lifecycle, asynchronous refresh failure/cancellation, multi-selection, and keyboard bindings. Offscreen layout checks cover the configured headless IDE theme at 640px and 1000px widths. Theme switching requires live IDE verification. Build, synchronization, and recovery dialogs are also painted offscreen; the alignment diff editor requires an on-screen window and has layout checks only. Generated snapshots live under `build/reports/dialog-visuals`.

These are not live desktop interaction tests. Native desktop automation is unavailable in this session. Before release, run the plugin in a sandbox IDE with disposable test repositories and perform this checklist in both the current light and dark IDE themes:

1. Narrow and resize each build, synchronization, preview, and recovery dialog; verify that every action stays reachable.
2. Navigate the tree and dialogs using Tab, Shift+Tab, Enter, Escape, and Shift+F10; check accessible names with a screen reader.
3. Run builds/rebases and verify animated progress repainting. Stop during a command, close during refresh, and close an unrelated idle dialog.
4. Create a source conflict and a stash conflict; open **Resolve Conflicts…**, resolve/cancel in the native merge editor, and verify the recovery instructions and retained stash.
5. Continue a paused rebase with IntelliJ's Git tools, restore local work according to the retained instructions, and preview alignment. Confirm that editing a POM or changing branches after preview prevents application.
6. Inspect and delete a disposable recovery-copy run after confirmation; confirm that the source files, Git refs, and stashes remain.
