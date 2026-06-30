# Issue #319 Implementation Review

- Date: 2026-07-01
- Issue: https://github.com/bluetape4k/bluetape4k-workshop/issues/319
- Spec: `docs/superpowers/specs/2026-07-01-issue-319-ktor-exposed-rest-design.md`
- Plan: `docs/superpowers/plans/2026-07-01-issue-319-ktor-exposed-rest-plan.md`
- Review gate: Step 6-R

## Verdict

| Severity | Count | Status |
|---|---:|---|
| P0 | 0 | PASS |
| P1 | 0 | PASS |
| P2 | 0 | PASS |
| P3 | 2 | Documented residual risk |

Step 6-R may proceed because P0=0 and P1=0.

## Perspective Findings

### 1. Transaction And Data Consistency

- P0/P1: none.
- Evidence: The example uses `bluetape4k-exposed-ktor` transaction helpers and
  PostgreSQL-backed `bluetape4k-testcontainers` tests instead of an H2
  substitute.
- Evidence: The rollback endpoint creates a row and then throws inside the
  transaction; the test verifies the error response and an empty PostgreSQL
  result set after rollback.
- Evidence: Request validation is performed before entering Exposed
  transactions, so ordinary invalid input is not misclassified as an Exposed
  transaction failure.

### 2. Stability And Cancellation

- P0/P1: none.
- Evidence: `/api/failures/cancelled` verifies cancellation is not converted to
  `EXPOSED_TRANSACTION_FAILED` or `EXPOSED_DATABASE_UNAVAILABLE`.
- Evidence: `KtorExposedRestResources` owns the Hikari datasource and JDBC
  dispatcher and closes both from `ApplicationStopped`.

### 3. Security And Error Redaction

- P0/P1: none.
- Evidence: SQL failure tests include fake JDBC URL, user, password, and SQL
  text; responses are asserted to omit those sensitive strings.
- Evidence: Transaction rollback responses are asserted to omit the failed
  title and PostgreSQL connection details.

### 4. Operator And CI

- P0/P1: none.
- Evidence: `.github/workflows/Examples.yml` includes the module path filter,
  container-backed Gradle task, and test artifact paths.
- Evidence: `scripts/smoke-validate.sh data-access-full` includes the new
  `:ktor-exposed-rest:test` task and was corrected to use actual project paths
  for the existing Exposed/Javers examples.
- Evidence: `./gradlew projects --console=plain` reported
  `:ktor-exposed-rest` and completed successfully.

### 5. Developer/API

- P0/P1: none.
- Evidence: The module uses root catalog aliases and versionless bluetape4k
  coordinates; it does not pin a local bluetape4k version.
- Evidence: Test resources include `junit-platform.properties` and
  `logback-test.xml`, matching the repo's module conventions.
- Evidence: Public-facing README artifacts are paired as `README.md` and
  `README.ko.md`.

### 6. User/Caller

- P0/P1: none.
- Evidence: The README pair explains the dependency boundary, architecture,
  transaction flow, route examples, local PostgreSQL Testcontainers validation,
  manual PostgreSQL environment variables, rollback behavior, and error
  redaction contract.
- Evidence: Root README tables include the new data-access example with focused
  test command in both English and Korean.

## Diagram Verification

Touched diagrams:

- `docs/images/readme-diagrams/ktor-exposed-rest-readme-architecture-01.svg`
- `docs/images/readme-diagrams/ktor-exposed-rest-readme-architecture-01.png`
- `docs/images/readme-diagrams/ktor-exposed-rest-readme-sequence-01.svg`
- `docs/images/readme-diagrams/ktor-exposed-rest-readme-sequence-01.png`

Evidence:

- `xmllint --noout`: PASS for both SVG files.
- `cairosvg -s 2`: PASS for both PNG render outputs.
- `node scripts/validate-readme-diagram-qa.mjs
  docs/images/readme-diagrams/ktor-exposed-rest-readme-architecture-01.svg
  docs/images/readme-diagrams/ktor-exposed-rest-readme-sequence-01.svg`: PASS,
  `targets=2`, `weak_reference_rows=0`.
- Architecture marker audit: PASS, `markers_checked=7`,
  `marker_failures=0`.
- Architecture connector audit: PASS, `connectors=7`, `cards=7`,
  `intrusions=0`, `crossings=0`.
- Architecture mixed-corner audit: PASS, `paths=4`, `q_bends=4`,
  `failures=0`.
- Sequence marker audit: PASS, `markers_checked=8`, `marker_failures=0`.
- Sequence fallback audit: PASS, `labels=8`, `numbers=8`,
  `monotonic=true`, `alt_fill_failures=0`.
- Sequence style reference audit: PASS, `sequence_files=1`.
- Full-size PNG eye inspection: PASS for both generated PNG files after the
  final connector, marker, text alignment, transparent `alt`, and palette
  checks.

Icon evidence:

- `testcontainers.postgresql`: official PostgreSQL icon from
  `docs/icons/testcontainers/database/postgresql.svg` in `bluetape4k-wiki`,
  with catalog metadata embedded in the architecture SVG.

## Validation Evidence

- `./gradlew :ktor-exposed-rest:compileKotlin`: PASS.
- `./gradlew :ktor-exposed-rest:cleanTest :ktor-exposed-rest:test
  --rerun-tasks --warning-mode all --console=plain --max-workers=1`: PASS,
  `SUCCESS: Executed 6 tests in 3s`.
- `./scripts/smoke-validate.sh data-access-full`: PASS,
  `BUILD SUCCESSFUL in 2m 8s`.
- `./scripts/smoke-validate.sh stale-check`: PASS, active modules
  `91 (expected: 91)`, stale refs `0`, broken image links `0`.
- `./gradlew projects --console=plain`: PASS, new project present.
- `actionlint .github/workflows/Examples.yml`: PASS.
- `git diff --check`: PASS.

## Notes And Residual Risk

- P3: IDE diagnostics were not available in this session. The fallback was
  fresh Gradle compilation, focused Testcontainers tests, and smoke validation.
- P3: Root Gradle deprecation warnings remain visible under Gradle 9.6, but
  they are emitted by pre-existing root build logic and are outside this
  issue's touched code.

## Open Questions

None.
