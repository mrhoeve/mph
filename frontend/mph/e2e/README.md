# End-to-end tests

The Playwright suite runs against the packaged Spring Boot application and a disposable Maven workspace.
It never reads or writes the developer's normal `~/.mph` settings because the server is started with an
isolated `mph.settings.directory`.

From the repository root:

1. Build the application with `mvn package -DskipTests`.
2. Install the Chromium runtime once with `cd frontend/mph` followed by `npx playwright install chromium`.
3. Run `npm run test:e2e` from `frontend/mph`.

Use `npm run test:e2e:headed` to watch the browser or `npm run test:e2e:ui` for Playwright UI mode.
Failure traces, screenshots, videos, and the HTML report are ignored by Git.

The suite covers workspace selection and smoke checks, managed-property override filtering, modal keyboard
and overlay dismissal, bulk-update controls, and build-order project focus. Fixture mutations are deliberately
avoided so every journey can be repeated locally and in CI.
