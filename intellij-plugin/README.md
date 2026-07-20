# MPH IntelliJ IDEA plugin

This module provides Maven Project Helper as a native IntelliJ IDEA plugin for multi-module, multi-repository workspaces.

## Features

- Adds an **MPH** tool window.
- Reads the Maven projects already linked to the opened IDEA project.
- Groups Maven projects by their nearest registered Git root.
- Shows Maven coordinates and versions.
- Opens a project's `pom.xml` on double-click.
- Refreshes manually and after a `pom.xml` filesystem change.
- Adds **Update Dependent Maven Projects…** to the editor and Project-view context menus for `pom.xml` files.
- Previews every linked project that uses the selected project as a parent, dependency, or managed dependency.
- Updates literal and locally property-managed versions as one undoable IntelliJ command after confirmation.
- Reports inherited properties and references without a local version instead of modifying them unsafely.
- Supports selecting multiple modules or complete repositories in the **MPH** tool window.
- Adds or removes one shared version prefix and optionally updates dependent references across all linked projects as one undoable operation.
- Runs configurable Maven goals sequentially, preferring each repository's Maven wrapper and streaming output into an IntelliJ console.
- Synchronizes prefixed feature branches with `origin/develop`: fetches, safely updates the local `develop` reference, stashes tracked and untracked work, rebases, restores the stash, and reapplies the prefix to the current versions.

After an update, IntelliJ refreshes its linked Maven model automatically. All changes remain uncommitted and can be reverted together with **Undo**.

## Using the tool window

Open **View -> Tool Windows -> MPH**. Select modules with Ctrl-click (Cmd-click on macOS), or select a repository row to include all its modules.

- **Align Versions** previews the selected projects, adds or removes a prefix, and can update references in every linked Maven project.
- **Build** runs `clean install` by default. Unit and integration tests can be enabled in the dialog. Selecting a repository row builds its root POM; selecting individual modules builds those POMs.
- **Sync with develop** is available for projects in Git repositories. It refuses protected branches (`main`, `master`, and `develop`), detached heads, active Git operations, divergent local `develop` branches, and repositories without `origin/develop`.

Repositories are processed sequentially. Version-only `pom.xml` conflicts are resolved from the updated `develop` version. Source-code and structural POM conflicts are left in Git's rebase-conflict state for manual resolution, processing continues with the other repositories, and final version alignment is skipped. Any MPH-created stash is retained when automatic restoration cannot finish safely. Nothing is pushed or committed by the plugin.

## Run the development IDE

From this directory, run:

```shell
./gradlew runIde
```

Open or import a multi-module Maven workspace in the development IDE, then open **View -> Tool Windows -> MPH**.

## Verification

From the repository root, the recommended local command is:

```powershell
.\build-plugin-local.cmd
```

The script locates JDK 21 even when `JAVA_HOME` points to another JDK. Pass `-JavaHome 'D:\path\to\jdk-21'` or set `MPH_JAVA_HOME_21` if JDK 21 is installed in a non-standard location.

The equivalent direct Gradle command is:

```shell
./gradlew test verifyPlugin buildPlugin
```

Generate the JaCoCo-compatible Kover XML report used by the combined project SonarQube analysis with:

```shell
./gradlew koverXmlReport
```

The prototype currently targets IntelliJ IDEA 2026.1.

The plugin version is derived from the root Maven `pom.xml`, so local application and plugin packages always use the same version. Release verification uses the `releaseBuild` Gradle property to remove `-SNAPSHOT`, or supplies an explicit `mphVersion` when the manual release version is overridden.
