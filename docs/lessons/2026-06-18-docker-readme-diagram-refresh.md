# Docker README Diagram Refresh

## Context

`docker/compose-demo` still exposed Graphviz wording and a README `FIXME` even
though the source already made the useful reader contract clear: each JUnit test
loads a module-local Docker Compose file through Testcontainers
`DockerComposeContainer`.

## Decision

Explain the active Compose services directly from the source files. Show
`docker-compose-redis.yml`, `docker-compose-postgres.yml`, and
`docker-compose-multiple.yml` as different service sets, and keep the commented
Redis branch in `multiple.yml` visually distinct from active Redis in the
single-service test.

Use service icon cards for Docker/Testcontainers, Redis, PostgreSQL, and
Elasticsearch. Remove Graphviz assets and old non-README diagrams in the module
pass instead of leaving stale alternatives beside the README assets.

## Outcome

The README now presents the module as a Compose service exposure example, not a
generic test infrastructure slice. The sequence diagram shows the actual
`DockerComposeContainer` lifecycle: load file, declare exposed service, wait for
listening port, resolve mapped port, and assert client behavior.

For `docker/compose-plugin-demo`, the useful contract is different: Gradle owns
the Compose lifecycle through `dockerCompose`, then exposes service host/port
data to the test JVM. Keep Redis/PostgreSQL as the wired test services, and show
Elasticsearch as a present compose file only when it is not part of
`useComposeFiles`.

## Verification

- Read the README, Kotlin tests, Gradle dependencies, and all compose files.
- Rendered SVG diagrams to PNG with CairoSVG.
- Visually inspected the rendered architecture and sequence as a contact sheet.
- Checked README image links, Graphviz references, SVG XML, and `git diff --check`.

## Future Guidance

Do not keep `FIXME` or work-log language in user-facing READMEs. If a legacy API
is unreliable but still documented for understanding, describe the maintained
alternative and the source-backed contract the example still teaches.
