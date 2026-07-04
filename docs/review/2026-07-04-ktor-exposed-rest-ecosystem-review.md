# Ktor Exposed REST Ecosystem Review

Date: 2026-07-04
Scope: `ktor/exposed-rest`

## Summary

This review tightens the Ktor + Exposed REST example against the bluetape4k ecosystem code-pattern rules:

- Replace stdlib `requireNotNull` path/test guards with `io.bluetape4k.support.requireNotNull`.
- Preserve existing `requireNotBlank`, `exposedJdbcTransaction`, `respondApiError`, Ktor testing helpers, and Testcontainers PostgreSQL launcher usage.
- Keep rollback and SQL error sanitization behavior unchanged.

## 7-Tier Review

| Tier | Verdict | Evidence |
|---|---|---|
| 1 Security | PASS | SQL failure and rollback tests still prove JDBC URL, username, password, and SQL text are not exposed. |
| 2 Correctness | PASS | CRUD, rollback, health/readiness, cancellation, and validation behavior remain covered by the PostgreSQL-backed route tests. |
| 3 Architecture | PASS | Routes continue to use `exposedJdbcTransaction`; repository and Ktor resource boundaries are unchanged. |
| 4 Code Quality | PASS | Validation uses bluetape4k `requireNotBlank` and `requireNotNull`; tests use bluetape4k Ktor/assertion helpers and singleton PostgreSQL launcher. |
| 5 Tests | PASS | `KtorExposedRestApplicationTest` runs against Testcontainers PostgreSQL with `--max-workers=1`. |
| 6 Docs/Examples | PASS | README semantics remain accurate because only helper selection changed. |
| 7 Evidence | PASS | Targeted Gradle test, pattern scan, and `git diff --check` passed in the module worktree. |

P0/P1 findings: 0.

## Verification

- `./gradlew :ktor-exposed-rest:test --console=plain --max-workers=1` passed: 6 tests executed.
- `git diff --check` passed.
- `rg -n "!!|\brequire\(|Thread\.sleep|runBlocking|assertThrows|kotlin\.test|GenericContainer|println\(" ktor/exposed-rest -g '*.kt'` returned no matches.

