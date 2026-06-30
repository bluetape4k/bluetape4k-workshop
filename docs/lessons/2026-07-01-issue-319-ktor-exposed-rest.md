# Issue #319 Ktor Exposed REST

- Context: Added a Ktor REST workshop module that demonstrates
  `bluetape4k-exposed-ktor` JDBC transaction helpers with a real PostgreSQL
  Testcontainers database.
- Decision: Use `PostgreSQLServer.Launcher.postgres` instead of H2 so learners
  see the same transaction, readiness, rollback, and SQL error behavior they
  will use in production-like examples.
- Outcome: The focused module tests execute six PostgreSQL-backed scenarios,
  including CRUD, rollback, readiness, cancellation, validation, and sensitive
  SQL error redaction.
- Smoke lesson: Before adding a new task to `scripts/smoke-validate.sh`, verify
  the project paths with `./gradlew projects --console=plain`. Old display
  names and directory names are easy to confuse in grouped smoke commands.
- Diagram lesson: For new uncommitted SVG assets, `smoke-validate.sh
  diagram-qa` can be weak because it discovers changed committed paths. Run
  `node scripts/validate-readme-diagram-qa.mjs <new-svg...>` explicitly and
  inspect the rendered PNGs at full size before claiming checklist completion.
- Future agents: Keep sequence diagrams on the current best-practices palette:
  numbered labels above lines, matching arrowhead and line colors, transparent
  `alt` bodies, centered card text, and SVG metadata that lets repo validators
  audit markers and connectors.
