# Plugin behavior contract

These fixtures live entirely inside the plugin. They record migration expectations without importing the deprecated application, its fixtures, or its Maven build.

- Normalize a feature prefix once, preserving formatting and unrelated properties.
- Realign dependency and parent references together.
- Resolve inherited module usages without inserting an explicit project version.
- Build prerequisites before selected dependents; selecting the reactor subsumes its modules.
- Auto-resolve committed version-only conflicts using develop's side; mixed/source conflicts require the native IDE merge editor. Real Git integration tests separately verify that stash conflicts remain manual.
