# Maven Project Helper (MPH)

[![Quality gate](https://sonarcloud.io/api/project_badges/quality_gate?project=mrhoeve_mph)](https://sonarcloud.io/summary/new_code?id=mrhoeve_mph)

**Maven Project Helper (MPH)** is a powerful local tool designed for Java and Kotlin developers to streamline the management of complex Maven multi-module project ecosystems.

## Purpose

Managing dozens of microservices and shared libraries can be a daunting task. MPH lightens this burden by providing a centralized dashboard to:

- **Analyze Dependencies**: Visualize the relationships between your projects and modules with interactive dependency graphs (powered by Mermaid).
- **Security Scanning**: Scan your projects for vulnerabilities using Nexus IQ integration, with direct links to specific security reports for each scan.
- **Software Bill of Materials (SBOM)**: Generate and view standard-compliant SBOMs (CycloneDX 1.5) for your projects. Explore all direct and transitive dependencies in an interactive, searchable view.
- **Determine Build Order**: Automatically calculate the correct topological build order for your projects, identifying which can be built concurrently.
- **Bulk Version Updates**: Effortlessly update version properties across multiple projects and their dependent usages. Support for manual version selection and automatic discovery of the latest Git tags ensures your ecosystem stays synchronized.
- **Maven Build Execution**: Run Maven builds directly from the app with support for parallel execution, real-time log streaming, and status tracking.
- **Git Integration**: Automatically handle branch creation and management during bulk updates. Visualize how many commits your branch is ahead or behind `develop` and easily merge `develop` into your work branch.
- **Spring Boot Upgrades**: Discover and apply newer Spring Boot versions across your projects.

## Key Features

- **Nexus IQ Security Analysis**: Integrated vulnerability scanning with Nexus IQ. Identify security risks in your dependencies and receive specific remediation versions. The app automatically resolves internal IDs to link directly to the correct report.
- **Standard-Compliant SBOMs**: Export your project's Software Bill of Materials in CycloneDX JSON or XML formats, ensuring compliance with security standards.
- **Smart Remediation**: Proactively warns about vulnerable versions when performing property overrides and suggests safe upgrade paths.
- **Excel Export with Concurrency**: Export your build order to Excel, complete with concurrent build steps and dependency mapping to optimize your CI/CD pipelines.
- **Jenkinsfile-Driven Scanning**: Nexus IQ scans are intelligently targeted at projects containing valid `Jenkinsfile` pipeline definitions.
- **Local-Only Tool**: Runs entirely on your machine, leveraging your existing Java/Kotlin development environment.
- **Fresh Scans**: Always performs a fresh scan of your project directories to ensure you're working with the most up-to-date information.
- **Expandable Project Tree**: A compact, hierarchical view of your project roots and modules for easy navigation.
- **Interactive Graphs**: Focus on specific projects to see their immediate dependencies and dependents, reducing noise in large ecosystems.
- **Elegant Toast Notifications**: Real-time feedback for system operations, errors, and informational updates.

## Created with Junie

This application was developed with the invaluable assistance of **Junie**, the autonomous coding agent by JetBrains.

[<img src="https://resources.jetbrains.com/storage/products/company/brand/logos/jb_beam.png" width="100" alt="JetBrains Logo">](https://www.jetbrains.com)

We love JetBrains tools, and Junie has been instrumental in bringing MPH to life!

## Developed with OpenAI Codex

This project is also developed and maintained with the assistance of **OpenAI Codex**, an AI coding agent used to explore the codebase, implement changes, and verify their correctness.

[<img src="https://images.ctfassets.net/kftzwdyauwt9/77tJ5U1tgxHMZflZ5m4Z24/ace4d8b6ad200d87ebcb69c466344343/Blossom_4k_Icon_1.png?fm=webp&q=90&w=256" width="100" alt="OpenAI Logo">](https://openai.com/codex/)

## Continuous Integration

GitHub Actions runs the frontend and backend unit tests on every push and pull request. Frontend coverage is written as LCOV and backend coverage as JaCoCo XML.

Every push also builds the application and runs SonarQube Cloud analysis when its repository settings are available. Releases are created only when the workflow is started manually from the `main` branch using GitHub Actions' **Run workflow** button.

The CI-based scan analyzes both `src/main/kotlin` and `frontend/mph/src`. It imports backend coverage from JaCoCo XML and frontend coverage from LCOV. Before enabling it, open the SonarQube Cloud project and turn off **Administration → Analysis Method → Automatic Analysis**; automatic and CI-based analysis cannot be enabled together.

Configure the following under **GitHub repository → Settings → Secrets and variables → Actions**:

- On the **Secrets** tab, create `SONAR_TOKEN` using a SonarQube Cloud token whose owner can execute analysis for this project. Generate a personal token under **SonarQube Cloud → account menu → My Account → Security**, and copy it when it is shown because it cannot be retrieved later.
- On the **Variables** tab, create `SONAR_ORGANIZATION` with the organization key shown on the SonarQube Cloud organization page.
- On the **Variables** tab, create `SONAR_PROJECT_KEY` with the project key shown under **SonarQube Cloud project → Project Information**.

Do not store the token as a variable: GitHub variables are not masked and are intended for non-sensitive values. The scan is skipped when any required setting is unavailable, such as for pull requests from forks.

## Local verification build

Run the complete local build from PowerShell:

```powershell
.\build-local.cmd
```

The script performs a locked `npm ci`, installs the Playwright Chromium runtime when needed, runs the frontend unit tests with coverage, runs `mvnw clean verify`, and finally runs the full-stack Playwright tests against the packaged application. Use `-SkipPlaywright` on a machine that cannot launch a browser, or `-SkipPlaywrightBrowserInstall` when Chromium is already managed separately.

To include analysis against the internal SonarQube instance, create a `.sonar-token` file in the repository root containing only your token. This filename is ignored by Git. Alternatively, set `SONAR_TOKEN` in the current process environment. Then run:

```powershell
.\build-local.cmd -Sonar
```

The token is passed to the scanner through the `SONAR_TOKEN` environment variable and is therefore not included in Maven's command-line arguments. The defaults are project key `mrhoeve_mph`, project name `Maven Project Helper`, and server `https://sonarqube.hictsapps.nl`. Override them when necessary:

```powershell
.\build-local.cmd -Sonar `
  -SonarHostUrl 'https://sonarqube.example.org' `
  -SonarProjectKey 'example_project' `
  -SonarProjectName 'Example Project'
```

## License

This project is licensed under the [MIT License](LICENSE).

## Reporting Issues

Found a bug or have a feature request? Please report them to our [Issue Tracker](https://github.com/hicts/mph/issues).

---

*Built for developers, by a developer (and Junie and OpenAI Codex).*
