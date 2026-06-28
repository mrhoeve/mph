### Project Context: Maven Project Helper (MPH)

#### Project Overview
MPH is a tool designed to scan a local directory for Maven multi-module projects, analyze their inter-dependencies, and facilitate bulk version updates across projects.

#### Architecture
- **Backend**: Kotlin / Spring Boot
  - `MavenProjectService`: Core logic for scanning, analyzing, and updating POM files.
  - `MavenProjectController`: REST endpoints for analysis and version updates.
  - `ScanProjectUtil`: Utility for recursive POM discovery using `MavenXpp3Reader`.
- **Frontend**: Angular / TypeScript
  - Features a two-pane layout: recursive project tree on the left, dependency/usage analysis on the right.
  - Supports "one-click" updates to sync dependent projects with the latest available version.

#### Test Data Environment
Location: `src/test/resources/test-data/`
- **multi-module-project**: Contains core parents (`client-parent`, `service-parent`) and a `common` project.
- **Dummy Projects (a-project to j-project)**:
  - 10 generated projects representing a microservice ecosystem.
  - Each has `api`, `service`, and `client` modules.
  - **Structure**:
    - `api` & `client`: Inherit from `com.example:client-parent`.
    - `service`: Inherits from `com.example:service-parent`.
    - `client` depends on local `api`.
    - `service` depends on local `api` and potentially other projects' `client` modules.
  - **Versioning**: All dependency versions are managed via `<properties>` in the POM files (e.g., `${a-project-client.version}`).

#### Operational State
- Scans are always fresh; no data is cached for the application's runtime.
- Configuration (base path, scan depth) is stored in `~/.mph/settings.properties`.
- Default scan depth: 3.
