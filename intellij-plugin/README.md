# MPH IntelliJ IDEA plugin prototype

This module contains the first read-only prototype of Maven Project Helper as a native IntelliJ IDEA plugin.

## Prototype scope

- Adds an **MPH** tool window.
- Reads the Maven projects already linked to the opened IDEA project.
- Groups Maven projects by their nearest registered Git root.
- Shows Maven coordinates and versions.
- Opens a project's `pom.xml` on double-click.
- Refreshes manually and after a `pom.xml` filesystem change.
- Adds **Update Dependent Maven Projects…** to the editor and Project-view context menus for `pom.xml` files.
- Previews every linked project that uses the selected project as a parent, dependency, or managed dependency.

The dependency preview deliberately does not modify POM files yet. Its next implementation step will reuse the preview as confirmation before applying surgical, undoable version updates.

## Run the development IDE

From this directory, run:

```shell
./gradlew runIde
```

Open or import a multi-module Maven workspace in the development IDE, then open **View -> Tool Windows -> MPH**.

## Verification

```shell
./gradlew test verifyPlugin buildPlugin
```

Generate the JaCoCo-compatible Kover XML report used by the combined project SonarQube analysis with:

```shell
./gradlew koverXmlReport
```

The prototype currently targets IntelliJ IDEA 2026.1.
