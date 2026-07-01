# Maven Project Helper (MPH)

**Maven Project Helper (MPH)** is a powerful local tool designed for Java and Kotlin developers to streamline the management of complex Maven multi-module project ecosystems.

## Purpose

Managing dozens of microservices and shared libraries can be a daunting task. MPH lightens this burden by providing a centralized dashboard to:

- **Analyze Dependencies**: Visualize the relationships between your projects and modules with interactive dependency graphs (powered by Mermaid).
- **Determine Build Order**: Automatically calculate the correct topological build order for your projects, ensuring that base dependencies are built before the projects that rely on them.
- **Bulk Version Updates**: Effortlessly update version properties across multiple projects and their dependent usages. Support for manual version selection and automatic discovery of the latest Git tags ensures your ecosystem stays synchronized.
- **Maven Build Execution**: Run Maven builds directly from the app with support for parallel execution, real-time log streaming, and status tracking.
- **Git Integration**: Automatically handle branch creation and management during bulk updates, and keep your local branches in sync with `develop`.
- **Spring Boot Upgrades**: Discover and apply newer Spring Boot versions across your projects.

## Key Features

- **Local-Only Tool**: Runs entirely on your machine, leveraging your existing Java/Kotlin development environment.
- **Fresh Scans**: Always performs a fresh scan of your project directories to ensure you're working with the most up-to-date information.
- **Expandable Project Tree**: A compact, hierarchical view of your project roots and modules for easy navigation.
- **Interactive Graphs**: Focus on specific projects to see their immediate dependencies and dependents, reducing noise in large ecosystems.
- **Elegant Toast Notifications**: Real-time feedback for system operations, errors, and informational updates.

## Created with Junie

This application was developed with the invaluable assistance of **Junie**, the autonomous coding agent by JetBrains.

[<img src="https://resources.jetbrains.com/storage/products/company/brand/logos/jb_beam.png" width="100" alt="JetBrains Logo">](https://www.jetbrains.com)

We love JetBrains tools, and Junie has been instrumental in bringing MPH to life!

## License

This project is licensed under the [MIT License](LICENSE).

## Reporting Issues

Found a bug or have a feature request? Please report them to our [Issue Tracker](https://github.com/hicts/mph/issues).

---

*Built for developers, by a developer (and Junie).*
