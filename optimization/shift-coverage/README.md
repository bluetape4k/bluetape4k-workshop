# Shift Coverage

This Spring Boot reference application demonstrates synthetic multi-site worker
and shift coverage with a human-confirmed shift swap. The planner reads an
immutable canonical snapshot and returns a deterministic proposal; only manager
approval and swap acceptance can change the assignment projection.

## Boundary

- The default `demo` profile binds to loopback and uses the deterministic fake
  adapter. It does not contact Timefold, store provider credentials, or perform
  autonomous reassignment.
- Hard rules cover overlap, unavailable workers, skills, minimum rest, started
  shifts, and pinned assignments. Gaps expose closed reason codes, signed
  minor-unit scores, and the plan revision.
- Local wall-clock boundaries reject DST gaps/ambiguities unless an explicit
  offset is supplied; source events stale the affected plan/generation without
  writing assignments.
- The `postgres` profile routes assignment approval/swap CAS and command
  idempotency claims through PostgreSQL/Testcontainers. Planner plans,
  generations, inbox callbacks, and the demo outbox remain bounded in-memory
  seams until their durable repositories are added; the default demo can start
  without a database.
- Java 25 virtual threads are reserved for blocking I/O. CPU planning uses a
  bounded four-worker/eight-queue admission path.
- Actuator health and Prometheus metrics expose only bounded `result` labels;
  worker, tenant, credential, and callback body values are never labels.

## Run and verify

```bash
./gradlew :optimization-shift-coverage:test --max-workers=1 --console=plain
./gradlew :optimization-shift-coverage:bootRun
curl -s http://127.0.0.1:8080/shift-coverage/
```

The `postgres` profile fails closed unless `SHIFT_COVERAGE_DATABASE_URL`,
`SHIFT_COVERAGE_DATABASE_USERNAME`, and `SHIFT_COVERAGE_DATABASE_PASSWORD` are
provided by the environment; no repository default JDBC URL or credential is
used.

Demo headers use `manager-demo`, `worker-a-demo`, or `worker-b-demo` with the
matching `X-Demo-Role`. Mutation requests require `Idempotency-Key` and the
loopback guard. The browser console is deliberately redacted and only shows
plan revision, coverage/gap, fairness, and closed reasons.

This consumer uses only the root `bluetape4k-dependencies` BOM and does not
depend on the sibling planning-contracts implementation.
