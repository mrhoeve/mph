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
- Sets a project and its modules to a manually entered version or the version found in the latest Git tag, then updates every linked usage.
- Optionally creates or checks out one shared Git branch before applying a bulk version prefix.
- Shows each repository's current branch and its ahead/behind status relative to `origin/develop`.
- Realigns dependent references to the selected projects' current versions without changing those project versions.
- Runs configurable Maven goals sequentially or in dependency-aware parallel stages, preferring each repository's Maven wrapper and streaming output into an IntelliJ console.
- Synchronizes prefixed feature branches with `origin/develop`: fetches, safely updates the local `develop` reference, stashes tracked and untracked work, rebases, restores the stash, and reapplies the prefix to the current versions.
- Explores incoming and outgoing Maven relationships, including dependencies outside the current IDEA workspace.
- Calculates repository build stages, highlights dependency cycles, builds in that order, and exports the plan as an Excel workbook.
- Shows effective managed version properties, filters local overrides, adds or removes overrides, upgrades Spring Boot parent or BOM versions, and retrieves Nexus IQ remediation recommendations for managed components.
- Inspects the resolved direct and transitive dependency tree and exports CycloneDX 1.5 JSON or XML SBOMs.
- Runs Nexus IQ evaluations, retrieves security-policy results, and links directly to the generated report.

After an update, IntelliJ refreshes its linked Maven model automatically. All changes remain uncommitted and can be reverted together with **Undo**.

## Using the tool window

Open **View -> Tool Windows -> MPH**. Select modules with Ctrl-click (Cmd-click on macOS), or select a repository row to include all its modules.

- **Align Versions** previews the selected projects, adds or removes a prefix, and can update references in every linked Maven project.
- **Update Version** accepts an explicit version or discovers it from the latest Git tag, updating modules and usages together.
- **Realign Versions** repairs linked references using every selected project's current version.
- **Build** runs `clean install` by default. Unit and integration tests can be enabled in the dialog. Selecting a repository row builds its root POM; selecting individual modules builds those POMs.
- **Sync with develop** is available for projects in Git repositories. It refuses protected branches (`main`, `master`, and `develop`), detached heads, active Git operations, divergent local `develop` branches, and repositories without `origin/develop`.
- **Dependencies** shows what the selected module uses and which linked modules use it. Double-click a linked module to open its POM.
- **Build Order** groups repositories into safe build stages, can pass the calculated order to the Maven build dialog, and exports an `.xlsx` plan.
- **Managed Versions** searches effective version properties, optionally shows only local overrides, and offers a Spring Boot upgrade when a parent or BOM is detected.
- **SBOM** shows the resolved Maven dependency tree and exports CycloneDX 1.5 JSON or XML.
- **Nexus IQ** evaluates a project whose `Jenkinsfile` calls `servicePipeline(...)` or `libraryPipeline(...)`, then displays its security-policy violations.

## Nexus IQ configuration

Open **Settings -> Tools -> Maven Project Helper**, or use the settings button in the MPH tool window. Configure the Nexus IQ server URL, optional username and password/token, and any application-ID prefix or suffix used by your organization. The password is stored in IntelliJ's Password Safe and is never written to the plugin settings file.

The application ID is derived from the selected repository's `Jenkinsfile`, matching the standalone application's behavior. Maven repository mirrors, credentials, local-repository location, and environment placeholders continue to come from the developer's normal Maven configuration because the plugin invokes the repository's Maven wrapper (or `mvn`) in that project directory.

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
