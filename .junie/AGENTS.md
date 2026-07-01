# Agent Context and Guidelines

This file serves as a permanent state of mind for Junie, as requested by the user.

## Project Context
For a detailed overview of the project structure, test data, and operational goals, please refer to:
[AI-data/context.md](../AI-data/context.md)

## Key Reminders
- The application is a **local-only tool** intended to run on individual developers' machines to lighten their daily tasks.
- The environment is a standard **Java/Kotlin developer setup**, meaning Maven, JDK, and other development tools are typically already available on the host system.
- The application must always perform a **fresh scan** of projects. Do not implement any caching or baseline loading that avoids scanning.
- Project dependency versions are managed via `<properties>` in POM files.
- The UI uses a two-pane layout for browsing projects and managing versions.
- Test projects are located in `src/test/resources/test-data/`.
