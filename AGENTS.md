# Project Guidelines

- Never use real people's names or personal email addresses in tests, test fixtures, sample data, or test documentation. Use clearly fictional placeholders such as `Test User` and `test.user@example.org` instead.

## IntelliJ IDEA compatibility

- Maintain a rolling support window covering the previous stable major IDEA release, the current stable major release, and the next major release once a testable preview is available. Patch releases do not shift this window.
- Compile against the oldest supported line and run Plugin Verifier against each supported line. Keep the IDE pins and build bounds in `intellij-plugin/gradle.properties` aligned with the documented window and plugin change notes.
- Do not remove the upper compatibility bound or advertise an unavailable/unverified future IDE line. Before extending support, run all tests and compatibility verification; include manual UI smoke testing before release.
