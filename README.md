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
- **Git Integration**: Automatically handle branch creation and management during bulk updates. Visualize how many commits your branch is ahead or behind `develop`, merge it, or safely rebase prefixed upgrade branches across multiple repositories.
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

## Rebase prefixed upgrade branches

Select the affected root projects and choose **Rebase on develop**. After confirmation, MPH validates that every selected module has the same version prefix, groups projects by Git repository, stashes tracked and untracked work, fetches `origin/develop`, and rebases each current feature branch. Version-only `pom.xml` conflict hunks are resolved from the current develop version; source-code and structural POM conflicts are deliberately left for manual resolution.

Processing continues when one repository needs attention. In that situation the repository remains in its Git conflict state, its MPH stash is preserved when necessary, and global version alignment is skipped. Follow the recovery instructions shown for that repository. After resolving all repositories, use **Version Update** to perform the deferred alignment manually. If every repository succeeds, MPH reapplies the original prefix to the new versions and updates all dependent modules automatically. It never pushes or creates an additional application commit, and version changes remain uncommitted.

## Created with Junie

This application was developed with the invaluable assistance of **Junie**, the autonomous coding agent by JetBrains.

[<img src="https://resources.jetbrains.com/storage/products/company/brand/logos/jb_beam.png" width="100" alt="JetBrains Logo">](https://www.jetbrains.com)

We love JetBrains tools, and Junie has been instrumental in bringing MPH to life!

## Developed with OpenAI Codex

This project is also developed and maintained with the assistance of **OpenAI Codex**, an AI coding agent used to explore the codebase, implement changes, and verify their correctness.

[<img src="https://images.ctfassets.net/kftzwdyauwt9/77tJ5U1tgxHMZflZ5m4Z24/ace4d8b6ad200d87ebcb69c466344343/Blossom_4k_Icon_1.png?fm=webp&q=90&w=256" width="100" alt="OpenAI Logo">](https://openai.com/codex/)

## Continuous Integration

GitHub Actions runs the frontend and backend unit tests on every push and pull request. Frontend coverage is written as LCOV and backend coverage as JaCoCo XML. The SonarQube scanner waits for the quality-gate result, so the **Build, test, and analyze** check fails when either the build or quality gate fails.

Every push also builds the application and runs SonarQube Cloud analysis. Releases use a separate **Release** workflow that must be started manually with `develop` selected in GitHub Actions. Selecting another branch fails before verification or publication. The workflow verifies the exact `develop` commit with the complete CI workflow, including its tests and SonarQube quality gate, and aborts if `develop` changes while verification is running.

After successful verification, the release workflow:

1. Confirms that `main` is an ancestor of the verified `develop` commit.
2. Removes `-SNAPSHOT` (or applies the manually supplied release version), creates the release commit, and tags it.
3. Creates a second commit containing the next development `-SNAPSHOT` version.
4. Atomically advances `main` to the tagged release commit, advances `develop` to the next development commit, and pushes the tag.
5. Creates the GitHub release from the already verified JAR.

For example, releasing `0.23-SNAPSHOT` creates `v0.23` on `main` and leaves `develop` at `0.24-SNAPSHOT`. Both versions can be overridden in the manual workflow form.

The CI-based scan analyzes both `src/main/kotlin` and `frontend/mph/src`. It imports backend coverage from JaCoCo XML and frontend coverage from LCOV. Before enabling it, open the SonarQube Cloud project and turn off **Administration → Analysis Method → Automatic Analysis**; automatic and CI-based analysis cannot be enabled together.

Configure the following under **GitHub repository → Settings → Secrets and variables → Actions**:

- On the **Secrets** tab, create `SONAR_TOKEN` using a SonarQube Cloud token whose owner can execute analysis for this project. Generate a personal token under **SonarQube Cloud → account menu → My Account → Security**, and copy it when it is shown because it cannot be retrieved later.
- On the **Variables** tab, create `SONAR_ORGANIZATION` with the organization key shown on the SonarQube Cloud organization page.
- On the **Variables** tab, create `SONAR_PROJECT_KEY` with the project key shown under **SonarQube Cloud project → Project Information**.

Do not store the token as a variable: GitHub variables are not masked and are intended for non-sensitive values. CI deliberately fails instead of silently skipping analysis when a required setting is unavailable. Consequently, pull requests from forks cannot pass this workflow unless you introduce a separate, securely configured fork-contribution workflow; GitHub does not expose repository secrets to untrusted fork workflows.

### Release GitHub App

The workflow uses a dedicated GitHub App rather than a personal access token. Create it as follows:

1. Open your GitHub account or organization settings, go to **Developer settings -> GitHub Apps**, and choose **New GitHub App**.
2. Give it a unique name such as `mph-release`, set the repository URL as its homepage, and disable webhooks; this app does not receive events.
3. Under **Repository permissions**, grant only **Contents: Read and write**. Metadata read access is added automatically. Do not grant organization permissions.
4. Create the app, record its **Client ID**, and generate one private key. GitHub downloads the key as a `.pem` file.
5. Install the app on the account that owns this repository and choose **Only select repositories**, selecting only `mph`.
6. Under **GitHub repository -> Settings -> Environments**, create an environment named `release`. Restrict its deployment branches to `develop`; optionally add a required reviewer for a second confirmation before publishing.
7. In that environment, create variable `RELEASE_APP_CLIENT_ID` containing the Client ID and secret `RELEASE_APP_PRIVATE_KEY` containing the complete PEM file, including its `BEGIN` and `END` lines.

Never commit the private key. If it is exposed, delete that key in the GitHub App settings immediately, generate a replacement, and update the environment secret.

### Required pull-request checks

To prevent merging until CI succeeds, create a repository ruleset under **GitHub repository -> Settings -> Rules -> Rulesets**:

1. Create a branch ruleset targeting only the long-lived branches `main` and `develop`. Do not use `~ALL`: feature branches must remain pushable so they can be used as pull-request source branches.
2. Enable **Require a pull request before merging**.
3. Enable **Require status checks to pass** and add `Build, test, and analyze` after that check has run at least once.
4. Enable **Require branches to be up to date before merging** if every PR should be tested against the latest target branch.
5. Enable **Restrict deletions** so GitHub's automatic head-branch cleanup cannot delete `main` or `develop` after a merge.
6. Add only the dedicated release GitHub App to the ruleset bypass list with **Always allow**. The release workflow needs this narrowly scoped exception to atomically update `main` and `develop`; human contributors remain subject to the PR and status-check requirements.

The ruleset is the enforcement layer: a workflow reports the check result, while GitHub branch rules decide whether that result blocks a merge. The release app token is generated only after the reusable CI workflow has passed and is restricted to the `release` environment.

## IntelliJ IDEA plugin prototype

The [`intellij-plugin`](intellij-plugin) module is the first prototype of MPH as a native IntelliJ IDEA plugin. Its **MPH** tool window uses IDEA's linked Maven projects and registered Git roots directly, shows project coordinates and versions grouped by repository, and opens a project's `pom.xml` on double-click. A context-aware **Update Dependent Maven Projects…** action now previews linked modules that reference the selected POM as a parent, dependency, or managed dependency.

The prototype deliberately keeps all write, build, and Git operations disabled while the project-model integration is validated. See the [plugin README](intellij-plugin/README.md) for development, installation, and verification commands.

## Local verification build

Run the complete local build from PowerShell:

```powershell
.\build-local.cmd
```

The script performs a locked `npm ci`, installs the Playwright Chromium runtime when needed, runs the frontend unit tests with coverage, tests and packages the IntelliJ plugin with Kotlin coverage and JetBrains compatibility verification, runs `mvnw clean verify`, and finally runs the full-stack Playwright tests against the packaged application. Use `-SkipPlaywright` on a machine that cannot launch a browser, or `-SkipPlaywrightBrowserInstall` when Chromium is already managed separately.

To include analysis against the internal SonarQube instance, create a `.sonar-token` file in the repository root containing only your token. This filename is ignored by Git. Alternatively, set `SONAR_TOKEN` in the current process environment. Then run:

```powershell
.\build-local.cmd -Sonar
```

The token is passed to the scanner through the `SONAR_TOKEN` environment variable and is therefore not included in Maven's command-line arguments. The combined analysis includes the Spring Boot backend, Angular frontend, and IntelliJ plugin, using their JaCoCo, LCOV, and Kover reports respectively. The defaults are project key `mrhoeve_mph`, project name `Maven Project Helper`, and server `https://sonarqube.hictsapps.nl`. Override them when necessary:

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
