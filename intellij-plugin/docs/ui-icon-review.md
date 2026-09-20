# Plugin UI and icon review

The audit covers every icon reference in the plugin's Kotlin UI, plugin.xml, custom toolbar assets, light/dark variants, and plugin branding. Icon names were checked against the actual SVGs in the bundled IntelliJ 2026.1 SDK. Contact sheets were rendered in light and dark backgrounds at native and doubled size. This is an asset and code audit, not an interactive IDE screenshot review.

## Corrections

| Location | Previous representation | Current representation |
| --- | --- | --- |
| Use latest Git tag | Git branch | Plain Git-tag glyph, separate from the version-update glyph |
| Sync with develop toolbar, heading and action | Generic branch | Shared commit-transfer icon |
| Nexus IQ heading | Success check before scanning | Neutral inspection eye, matching its toolbar action |
| Build dialog heading | Library folder | Build hammer, matching the toolbar |
| Build Order heading | Build hammer | Shared build-order graph, matching the toolbar |
| Dependencies toolbar and dependent-project action/header | Library or MPH branding | Dependency analyzer |
| Dependency groups | Download/upload symbols | Direction chevrons; labels retain the relationship meaning |
| SBOM heading | Generic module | Library collection, matching the toolbar |
| Bulk alignment heading | MPH branding | Alignment icon, matching the toolbar |
| Spring Boot upgrade | Library | Version-update tag |
| Graph Fit | Preview | Fit-content icon |
| Graph PNG export | Save all | Export, consistent with workbook and SBOM export |
| Graph zoom | Text plus/minus | Native zoom icons with tooltips and accessible names |
| Dependency cycles | Font-dependent warning character and warning-background color used as text | Native warning icons and normal readable text |
| Pending build/rebase | Paused inspection | Clock for waiting |
| Running build/rebase | One fixed spinner frame | IntelliJ animated icon, enabled in list renderers |

Shared operation meanings now live in `MphIcons`, reducing drift between toolbar and dialog choices. New custom assets use 16x16 SVGs and explicit dark variants consistent with the existing palette.

## Icons retained after inspection

- **Stop:** `AllIcons.Actions.Suspend` actually draws IntelliJ's red stop square; despite the name, it is correct. The semantic `MphIcons.Stop` alias documents that fact.
- **Remove Override:** `AllIcons.Actions.GC` actually draws a trash bin and is appropriate for deleting a local override.
- **Git tags:** `AllIcons.Nodes.Tag` draws XML brackets, so it is deliberately not used for Git tags.
- **Run, edit, refresh, expand/collapse, settings, information, web report, XML POM and export:** the native icons match their actions.
- **Repository folders, Maven modules, external libraries, managed properties and unresolved dependency warnings:** their existing node icons match what the rows represent.
- **Success, failure, conflict/skipped and cancelled states:** check, error, warning and cancel symbols remain appropriate, accompanied by explicit status text.
- **MPH tool-window, title, About and plugin-manager artwork:** retain the existing branding and its light/dark variants.
- **Confirmation questions:** retain the IDE's standard question icon.

## Other behavior corrected

The Maven build dialog's finished callback previously displayed success when a background exception occurred before results were returned. It now preserves the failure or cancellation outcome, with regression tests for both callback sequences. Closing an idle build dialog no longer cancels a build through the shared service.

Cancelling a latest-tag lookup now reports cancellation and restores the lookup button through the finished callback. Error reporting no longer queues an unnecessary later UI update that could arrive after the button was re-enabled.

Sequential Maven builds now honor the dependency stages supplied by the toolbar instead of falling back to the original selection order. A regression test selects a dependent before its prerequisite and verifies that the prerequisite is built first and duplicate selections are removed.

## Recommended next work

1. **Stop downstream work on prerequisite failure and reject cycles consistently.** Both build modes now respect supplied dependency order, but the stage runner still continues after failures. Skip dependent builds whose prerequisites failed, and apply Build Order's cycle checks to the normal Build action too. Otherwise dependents can compile against older locally installed artifacts.
2. **Give background operations ownership and a shared coordinator.** Build/rebase services use shared cancellation state. Prevent overlapping build runs and make each dialog cancel only its own operation. Use the same coordinator for branch and version changes; the idle-dialog fix does not solve every overlap.
3. **Reload Maven discovery before post-rebase alignment.** Reuse the tool window's refresh/realign flow so newly added modules and renamed coordinates are reflected in the synchronization plan. Keep the existing recovery snapshots and add an edit preview/transaction for dependent changes.
4. **Group the large toolbar and expand context actions.** Group inspection, version, Git/build and view actions with separators, and offer the relevant commands in the project context menu. Preserve the current ordering until a grouping is agreed; icons alone should not carry the navigation burden.
5. **Exercise the dialogs in both IDE themes.** In particular, inspect spinner repainting, narrow windows, keyboard navigation, screen-reader labels, and Stop/Close timing in a running IntelliJ instance. Static icon sheets cannot validate those interactions.

## Validation

The full plugin suite passed 107 tests before the final sequential-order fix. The final focused build/UI run passed 24 tests, including the new ordering regression. All 18 custom/branding SVG files parse and have matching light/dark variants. The icon contact sheets were visually inspected, and `git diff --check` passed. An interactive IDE check of animated repainting and narrow-window layout remains recommended.
