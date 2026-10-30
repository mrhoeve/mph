# Plugin behavior contract

These fixtures live entirely inside the plugin. They record migration expectations without importing the deprecated application, its fixtures, or its Maven build.

- Normalize a feature prefix once, preserving formatting and unrelated properties.
- Realign dependency and parent references together.
- Build prerequisites before selected dependents.
- Auto-resolve committed version-only conflicts using develop's side; mixed/source conflicts require the native IDE merge editor. Real Git integration tests separately verify that stash conflicts remain manual.

The adjacent bulk-alignment and dependency-analyzer test suites additionally cover inherited module versions and reactor/module selections.
