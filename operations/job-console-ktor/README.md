# Ktor Job Console Adapter

[한국어](README.ko.md) | English

This Java 25 adapter exposes the shared contract through Ktor Netty, Ktor SSE,
an application-owned poller/worker scope, and the shared operations UI.
Blocking JDBC and worker calls run on `Dispatchers.IO`, and coroutine
cancellation is rethrown.

## Safety boundary

Routes require `JOB_CONSOLE_DEMO=true`. `X-Demo-Tenant`,
`X-Demo-Submitter`, and `X-Demo-Operator` are trusted demo headers, not
production authentication. REST snapshots remain authoritative after every SSE
notification.

## Run

```bash
export POSTGRES_JDBC_URL=jdbc:postgresql://localhost:5432/postgres
export POSTGRES_USERNAME=postgres
export POSTGRES_PASSWORD=postgres
export JOB_CONSOLE_DEMO=true
# Optional advisory cancellation acceleration:
# export REDIS_URI=redis://localhost:6379
./gradlew :operations-job-console-ktor:run
```

## Verify

```bash
./gradlew :operations-job-console-ktor:test
./gradlew :operations-job-console-ktor:integrationTest --max-workers=1
```

## High-contention evidence

Run the shared core and Ktor adapter profiles with a running Docker daemon,
JDK 25, and at least 4 GiB of memory available to Gradle and the containers:

```bash
CI_RUN_ID=developer-ci-001
REFERENCE_RUN_ID=developer-reference-001
./gradlew highContentionCi -PhighContentionRunId="$CI_RUN_ID" --max-workers=1
./gradlew highContentionLocalReference -PhighContentionRunId="$REFERENCE_RUN_ID" --max-workers=1
```

The correctness gate is `highContentionCi`. `highContentionLocalReference`
records environment-specific execution observations; it does not rank
frameworks and does not establish production capacity. Canonical reports are
written under `build/reports/high-contention/<run-id>/`. Use a new run ID for
every command; local-reference execution also requires a clean worktree.

The Ktor adapter keeps blocking work on its application-owned dispatcher, while
PostgreSQL remains authoritative for leases, fencing, checkpoints,
deduplication, and terminal state. Toxiproxy cuts and restores only the advisory
Redis path, including old and newly opened connections; it does not replace
PostgreSQL authority or database failover.
