# Project Guidelines

- Never use real people's names or personal email addresses in tests, test fixtures, sample data, or test documentation. Use clearly fictional placeholders such as `Test User` and `test.user@example.org` instead.

## IntelliJ IDEA compatibility

- Target the previous stable major IDEA release, the current stable major release, and the next major release once a testable preview is available. This is a minimum target, not a maximum number of supported lines. Retain older lines as long as tests and compatibility verification pass and maintenance remains practical; do not drop them automatically when a new line is released. Patch releases do not change this policy.
- When a preview becomes stable, replace its verification pin with the final release and retain all still-supported older lines. Document and justify any necessary removal of an older line explicitly.
- Compile against the oldest supported line and run Plugin Verifier against each supported line. Keep the IDE pins and build bounds in `intellij-plugin/gradle.properties` aligned with the documented window and plugin change notes.
- Do not remove the upper compatibility bound or advertise an unavailable/unverified future IDE line. Before extending support, run all tests and compatibility verification; include manual UI smoke testing before release.
