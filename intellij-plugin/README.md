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
- Updates literal and locally property-managed versions as one undoable IntelliJ command after confirmation.
- Reports inherited properties and references without a local version instead of modifying them unsafely.

After an update, IntelliJ refreshes its linked Maven model automatically. All changes remain uncommitted and can be reverted together with **Undo**.

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
